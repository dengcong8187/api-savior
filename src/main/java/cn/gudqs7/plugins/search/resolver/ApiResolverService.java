package cn.gudqs7.plugins.search.resolver;

import cn.gudqs7.plugins.common.enums.HttpMethod;
import cn.gudqs7.plugins.common.pojo.resolver.CommentInfo;
import cn.gudqs7.plugins.common.resolver.comment.AnnotationHolder;
import cn.gudqs7.plugins.common.util.jetbrain.ExceptionUtil;
import cn.gudqs7.plugins.common.util.structure.PsiAnnotationUtil;
import cn.gudqs7.plugins.search.index.ApiIndexSnapshot;
import cn.gudqs7.plugins.search.index.ApiIndexSnapshot.FileApiEntry;
import cn.gudqs7.plugins.search.index.ApiIndexSnapshot.MethodApiEntry;
import cn.gudqs7.plugins.search.pojo.RequestMappingAnnotationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.stubs.StubIndexKey;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.concurrency.AppExecutorUtil;
import lombok.Getter;
import org.apache.commons.collections.CollectionUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫描项目中的接口方法, 结果同时缓存在内存与磁盘:
 * 内存缓存以 PSI 修改计数为版本; 磁盘缓存按源文件保存并记录文件时间戳,
 * 冷启动时只有文件发生变化的源文件才会重新解析, 其余直接读盘
 *
 * @author wq
 * @date 2022/5/28
 */
@Service(Service.Level.PROJECT)
public final class ApiResolverService {

    /**
     * 判断 Controller 用的注解短名, 与 JavaAnnotationIndex 的索引键一致
     */
    private static final List<String> CONTROLLER_ANNOTATIONS = Arrays.asList("Controller", "RestController");

    private static final String INDEX_DIR_NAME = "api-savior-index";

    private static final String INDEX_FILE_NAME = "api-index.bin";

    /**
     * 索引目录超过该时长未更新即视为废弃, 写入时顺带清理
     */
    private static final long INDEX_EXPIRE_MILLIS = TimeUnit.DAYS.toMillis(30);

    /**
     * 映射注解全限定名 -> 固定的请求方法, RequestMapping 的请求方法由注解属性决定, 故用 ALL 占位
     */
    private static final Map<String, HttpMethod> MAPPING_ANNOTATIONS = createMappingAnnotations();

    private final Project project;

    /**
     * 扫描串行化, 避免快速输入时并发全量扫描
     */
    private final Object scanLock = new Object();

    private final AtomicBoolean snapshotWriting = new AtomicBoolean(false);

    private volatile CacheEntry cache;

    private volatile ApiIndexSnapshot diskSnapshot;

    private volatile boolean diskSnapshotLoaded;

    public ApiResolverService(Project project) {
        this.project = project;
    }

    public static ApiResolverService getInstance(Project project) {
        return project.getService(ApiResolverService.class);
    }

    /**
     * 获取项目内全部接口; 磁盘读写都在 read action 之外, 只有需要解析 PSI 时才加读锁
     *
     * @param indicator 用于中断扫描, 可为 null
     */
    @NotNull
    public List<ApiNavigationItem> getApiNavigationItemList(@Nullable ProgressIndicator indicator) {
        CacheEntry entry = cache;
        long stamp = getModificationStamp();
        if (entry != null && entry.stamp == stamp) {
            return entry.items;
        }
        synchronized (scanLock) {
            entry = cache;
            stamp = getModificationStamp();
            if (entry != null && entry.stamp == stamp) {
                return entry.items;
            }
            ApiIndexSnapshot snapshot = loadSnapshot();
            RebuildResult result = ApplicationManager.getApplication().runReadAction(
                    (Computable<RebuildResult>) () -> rebuild(snapshot, indicator));
            cache = new CacheEntry(stamp, result.items);
            if (result.snapshot != null) {
                diskSnapshot = result.snapshot;
                diskSnapshotLoaded = true;
                scheduleSnapshotWrite(result.snapshot);
            }
            return result.items;
        }
    }

    @NotNull
    private RebuildResult rebuild(@Nullable ApiIndexSnapshot snapshot, @Nullable ProgressIndicator indicator) {
        Collection<VirtualFile> controllerFiles = collectControllerFiles();
        FileDocumentManager documentManager = FileDocumentManager.getInstance();
        List<ApiNavigationItem> items = new ArrayList<>(controllerFiles.size() * 8);
        Map<String, FileApiEntry> fileEntries = new HashMap<>(controllerFiles.size() * 2);
        boolean changed = snapshot == null;

        for (VirtualFile virtualFile : controllerFiles) {
            checkCanceled(indicator);
            String filePath = virtualFile.getPath();
            long fileStamp = getReusableFileStamp(virtualFile, documentManager);

            // 文件没动过, 直接复用磁盘快照, 全程不碰 PSI
            FileApiEntry cachedEntry = snapshot == null ? null : snapshot.find(filePath);
            if (cachedEntry != null && fileStamp >= 0 && cachedEntry.getFileStamp() == fileStamp) {
                items.addAll(toNavigationItems(cachedEntry));
                fileEntries.put(filePath, cachedEntry);
                continue;
            }

            changed = true;
            FileScanResult scanResult = scanJavaFile(virtualFile, indicator);
            items.addAll(scanResult.items);
            // 文件有未保存修改、无法定位或注解引用了常量时, 结果不可跨启动复用
            if (fileStamp >= 0 && scanResult.cacheable) {
                fileEntries.put(filePath, toFileApiEntry(filePath, fileStamp, scanResult.items));
            }
        }

        // 当前文件数少于快照时说明有文件被删除或改名, 需要回写以清掉失效条目
        boolean needWrite = changed || (snapshot != null && fileEntries.size() < snapshot.getFileEntries().size());
        ApiIndexSnapshot newSnapshot = needWrite ? new ApiIndexSnapshot(fileEntries) : null;
        return new RebuildResult(items, newSnapshot);
    }

    /**
     * 只取文件, 不构建 PsiAnnotation, 冷启动时开销远小于按注解取元素
     */
    @NotNull
    private Collection<VirtualFile> collectControllerFiles() {
        StubIndex stubIndex = StubIndex.getInstance();
        StubIndexKey<String, PsiAnnotation> indexKey = JavaAnnotationIndex.getInstance().getKey();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        Set<VirtualFile> controllerFiles = new LinkedHashSet<>();
        for (String annotationShortName : CONTROLLER_ANNOTATIONS) {
            Iterator<VirtualFile> iterator = stubIndex.getContainingFilesIterator(indexKey, annotationShortName, project, scope);
            while (iterator.hasNext()) {
                controllerFiles.add(iterator.next());
            }
        }
        return controllerFiles;
    }

    /**
     * 索引重建本身要在 read action 内(StubIndex 与 VFS 访问都有此要求), 磁盘 I/O 则留在 read action 之外
     *
     * @param indicator 用于中断扫描, 可为 null
     */
    @NotNull
    private FileScanResult scanJavaFile(@NotNull VirtualFile virtualFile, @Nullable ProgressIndicator indicator) {
        List<ApiNavigationItem> items = new ArrayList<>();
        PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
        if (!(psiFile instanceof PsiJavaFile)) {
            return new FileScanResult(items, false);
        }
        PsiJavaFile javaFile = (PsiJavaFile) psiFile;
        boolean cacheable = isCacheable(javaFile);
        for (PsiClass psiClass : javaFile.getClasses()) {
            checkCanceled(indicator);
            if (!isControllerClass(psiClass)) {
                continue;
            }
            String classQname = psiClass.getQualifiedName();
            try {
                items.addAll(getServiceItemList(psiClass, indicator));
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                String message = String.format("扫描接口时出错, 接口类全限定名为: %s; 错误信息为: %s", classQname, e.getMessage());
                ExceptionUtil.logException(e, message);
            }
        }
        return new FileScanResult(items, cacheable);
    }

    /**
     * 注解里的 path 若引用了常量, 修改常量类不会改变本文件的时间戳, 复用快照会得到过时的 url
     */
    private static boolean isCacheable(@NotNull PsiJavaFile javaFile) {
        for (PsiClass psiClass : javaFile.getClasses()) {
            if (!isControllerClass(psiClass)) {
                continue;
            }
            if (!isLiteralMapping(psiClass.getAnnotation(AnnotationHolder.QNAME_OF_MAPPING))) {
                return false;
            }
            for (PsiMethod psiMethod : psiClass.getMethods()) {
                for (PsiAnnotation psiAnnotation : psiMethod.getAnnotations()) {
                    if (!MAPPING_ANNOTATIONS.containsKey(psiAnnotation.getQualifiedName())) {
                        continue;
                    }
                    if (!isLiteralMapping(psiAnnotation)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isLiteralMapping(@Nullable PsiAnnotation psiAnnotation) {
        if (psiAnnotation == null) {
            return true;
        }
        return isLiteralValue(psiAnnotation.findAttributeValue("value"))
                && isLiteralValue(psiAnnotation.findAttributeValue("path"));
    }

    private static boolean isLiteralValue(@Nullable PsiAnnotationMemberValue value) {
        if (value == null || value instanceof PsiLiteralExpression) {
            return true;
        }
        if (value instanceof PsiArrayInitializerMemberValue) {
            for (PsiAnnotationMemberValue initializer : ((PsiArrayInitializerMemberValue) value).getInitializers()) {
                if (!isLiteralValue(initializer)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isControllerClass(@NotNull PsiClass psiClass) {
        for (PsiAnnotation psiAnnotation : psiClass.getAnnotations()) {
            String qname = psiAnnotation.getQualifiedName();
            if (qname == null) {
                continue;
            }
            if (CONTROLLER_ANNOTATIONS.contains(StringUtil.getShortName(qname))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 时间戳只在文件没有未保存修改时才可信, 否则内存里的内容已与磁盘不一致
     */
    private long getReusableFileStamp(@Nullable VirtualFile virtualFile, @NotNull FileDocumentManager documentManager) {
        if (virtualFile == null || !virtualFile.isValid()) {
            return -1;
        }
        Document document = documentManager.getCachedDocument(virtualFile);
        if (document != null && documentManager.isDocumentUnsaved(document)) {
            return -1;
        }
        return virtualFile.getModificationStamp();
    }

    @NotNull
    private List<ApiNavigationItem> toNavigationItems(@NotNull FileApiEntry fileEntry) {
        List<MethodApiEntry> methods = fileEntry.getMethods();
        List<ApiNavigationItem> items = new ArrayList<>(methods.size());
        for (MethodApiEntry methodEntry : methods) {
            HttpMethod httpMethod = parseHttpMethod(methodEntry.getHttpMethod());
            MethodPathInfo pathInfo = new MethodPathInfo(null, httpMethod, methodEntry.getMethodPath(),
                    methodEntry.getLocation(), methodEntry.getMethodDesc(), methodEntry.getParams());
            items.add(new ApiNavigationItem(project, httpMethod, methodEntry.getUrl(), pathInfo,
                    fileEntry.getFilePath(), methodEntry.getTextOffset()));
        }
        return items;
    }

    @NotNull
    private static FileApiEntry toFileApiEntry(String filePath, long fileStamp, List<ApiNavigationItem> items) {
        List<MethodApiEntry> methods = new ArrayList<>(items.size());
        for (ApiNavigationItem item : items) {
            MethodPathInfo pathInfo = item.getMethodPathInfo();
            methods.add(new MethodApiEntry(item.getUrl(), item.getHttpMethod().name(), pathInfo.getMethodPath(),
                    pathInfo.getLocation(), pathInfo.getMethodDesc(), pathInfo.getParams(), item.getTextOffset()));
        }
        return new FileApiEntry(filePath, fileStamp, methods);
    }

    @NotNull
    private static HttpMethod parseHttpMethod(@Nullable String name) {
        if (name == null) {
            return HttpMethod.ALL;
        }
        try {
            return HttpMethod.valueOf(name);
        } catch (IllegalArgumentException e) {
            return HttpMethod.ALL;
        }
    }

    @Nullable
    private ApiIndexSnapshot loadSnapshot() {
        if (diskSnapshotLoaded) {
            return diskSnapshot;
        }
        diskSnapshotLoaded = true;
        ApiIndexSnapshot snapshot = ApiIndexSnapshot.load(getIndexFilePath());
        diskSnapshot = snapshot;
        return snapshot;
    }

    /**
     * 索引状态, 供设置页展示; 只读文件元信息, 不触发任何扫描
     */
    @Getter
    public static final class IndexFileInfo {

        private final String filePath;
        private final boolean fileExists;
        private final long fileSize;
        private final long lastModified;
        /**
         * 内存缓存中的接口数, -1 表示本次会话尚未构建
         */
        private final int cachedItemCount;
        /**
         * 快照中的源文件数, -1 表示未知
         */
        private final int cachedFileCount;

        private IndexFileInfo(String filePath, boolean fileExists, long fileSize, long lastModified,
                              int cachedItemCount, int cachedFileCount) {
            this.filePath = filePath;
            this.fileExists = fileExists;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            this.cachedItemCount = cachedItemCount;
            this.cachedFileCount = cachedFileCount;
        }
    }

    @NotNull
    public IndexFileInfo getIndexFileInfo() {
        Path indexPath = getIndexFilePath();
        boolean fileExists = indexPath != null && Files.isRegularFile(indexPath);
        long fileSize = 0;
        long lastModified = 0;
        if (fileExists) {
            try {
                fileSize = Files.size(indexPath);
                lastModified = Files.getLastModifiedTime(indexPath).toMillis();
            } catch (Exception ignored) {
                // 读取文件属性失败时按"不存在"展示, 不影响使用
            }
        }
        CacheEntry entry = cache;
        ApiIndexSnapshot snapshot = diskSnapshot;
        return new IndexFileInfo(
                indexPath == null ? null : indexPath.toString(),
                fileExists,
                fileSize,
                lastModified,
                entry == null ? -1 : entry.items.size(),
                snapshot == null ? -1 : snapshot.getFileEntries().size()
        );
    }

    /**
     * 丢弃现有结果后强制全量重建, 返回重建出的接口数
     */
    public int rebuildNow(@Nullable ProgressIndicator indicator) {
        synchronized (scanLock) {
            RebuildResult result = ApplicationManager.getApplication().runReadAction(
                    (Computable<RebuildResult>) () -> rebuild(null, indicator));
            cache = new CacheEntry(getModificationStamp(), result.items);
            diskSnapshot = result.snapshot;
            diskSnapshotLoaded = true;
            if (result.snapshot != null) {
                scheduleSnapshotWrite(result.snapshot);
            }
            return result.items.size();
        }
    }

    /**
     * 清空内存与磁盘索引, 下次搜索时按需重建
     */
    public void invalidateCache() {
        synchronized (scanLock) {
            cache = null;
            diskSnapshot = null;
            // 标记为已加载, 避免重建前又读到刚被删掉的文件
            diskSnapshotLoaded = true;
            Path indexPath = getIndexFilePath();
            if (indexPath == null) {
                return;
            }
            try {
                Files.deleteIfExists(indexPath);
            } catch (Exception e) {
                ExceptionUtil.logException(e, "删除接口索引文件失败, 位置为: " + indexPath);
            }
        }
    }

    private void scheduleSnapshotWrite(@NotNull ApiIndexSnapshot snapshot) {
        Path indexPath = getIndexFilePath();
        if (indexPath == null || !snapshotWriting.compareAndSet(false, true)) {
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                snapshot.store(indexPath);
                cleanExpiredIndexes(indexPath.getParent());
            } catch (Exception e) {
                ExceptionUtil.logException(e, "写入接口索引失败, 索引位置为: " + indexPath);
            } finally {
                snapshotWriting.set(false);
            }
        });
    }

    /**
     * 项目被删除或长期不再打开时索引目录会留下来, 写入时顺带清理长期未更新的目录
     */
    private static void cleanExpiredIndexes(@Nullable Path currentProjectDir) {
        if (currentProjectDir == null) {
            return;
        }
        Path rootDir = currentProjectDir.getParent();
        if (rootDir == null || !Files.isDirectory(rootDir)) {
            return;
        }
        long expireBefore = System.currentTimeMillis() - INDEX_EXPIRE_MILLIS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir)) {
            for (Path projectDir : stream) {
                if (projectDir.equals(currentProjectDir) || !Files.isDirectory(projectDir)) {
                    continue;
                }
                Path indexFile = projectDir.resolve(INDEX_FILE_NAME);
                if (!Files.isRegularFile(indexFile)) {
                    continue;
                }
                if (Files.getLastModifiedTime(indexFile).toMillis() < expireBefore) {
                    FileUtil.delete(projectDir.toFile());
                }
            }
        } catch (Exception ignored) {
            // 清理是尽力而为, 失败不影响索引本身的使用
        }
    }

    @Nullable
    public Path getIndexFilePath() {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return null;
        }
        String projectKey = Integer.toHexString(basePath.hashCode());
        return PathManager.getSystemDir().resolve(INDEX_DIR_NAME).resolve(projectKey).resolve(INDEX_FILE_NAME);
    }

    @NotNull
    private List<ApiNavigationItem> getServiceItemList(@NotNull PsiClass psiClass, @Nullable ProgressIndicator indicator) {
        List<MethodPathInfo> methodPathList = new ArrayList<>(32);
        List<String> classParams = new ArrayList<>();
        Set<String> classPathSet = new LinkedHashSet<>(4);
        PsiAnnotation classRequestMappingAnnotation = psiClass.getAnnotation(AnnotationHolder.QNAME_OF_MAPPING);
        // 可能类上不加 @RequestMapping, 则代表 path = "/"
        if (classRequestMappingAnnotation != null) {
            RequestMappingAnnotationInfo classRequestMappingInfo = PsiAnnotationUtil.getAnnotationInfoByPojo(classRequestMappingAnnotation, RequestMappingAnnotationInfo.class);
            classPathSet.addAll(classRequestMappingInfo.getValueOrPath());
            List<String> params = classRequestMappingInfo.getParams();
            if (params != null) {
                classParams.addAll(params);
            }
        } else {
            classPathSet.add("/");
        }

        String classQname = psiClass.getQualifiedName();
        for (PsiMethod psiMethod : psiClass.getMethods()) {
            checkCanceled(indicator);
            try {
                methodPathList.addAll(getMethodPathInfoList(psiMethod, psiClass));
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                String methodName = psiMethod.getName();
                String methodQname = classQname + "#" + methodName;
                ExceptionUtil.logException(e, String.format("扫描接口时出错, 方法为: %s; 错误信息为: %s", methodQname, e.getMessage()));
            }
        }

        List<ApiNavigationItem> navigationItemList = new ArrayList<>(classPathSet.size() * methodPathList.size());
        for (String classPath : classPathSet) {
            String classPathPrefix = normalizeClassPath(classPath);
            for (MethodPathInfo methodPathInfo : methodPathList) {
                String methodPath = methodPathInfo.getMethodPath();
                if (methodPath.startsWith("/")) {
                    methodPath = methodPath.substring(1);
                }
                // 获取 params 信息, 先从方法的注解取, 取不到则尝试类的注解
                String param = "";
                List<String> methodParams = methodPathInfo.getParams();
                if (CollectionUtils.isNotEmpty(methodParams)) {
                    param = "?" + String.join("&", methodParams);
                } else if (CollectionUtils.isNotEmpty(classParams)) {
                    param = "?" + String.join("&", classParams);
                }
                String fullPath = classPathPrefix + methodPath + param;
                navigationItemList.add(new ApiNavigationItem(methodPathInfo.getPsiMethod(), methodPathInfo.getHttpMethod(), fullPath, methodPathInfo));
            }
        }
        return navigationItemList;
    }

    /**
     * 一次遍历方法注解, 避免为每种映射注解各查一遍; 注释信息对同一方法只解析一次
     */
    @NotNull
    private List<MethodPathInfo> getMethodPathInfoList(@NotNull PsiMethod psiMethod, @NotNull PsiClass psiClass) {
        List<MethodPathInfo> methodPathList = new ArrayList<>(2);
        for (PsiAnnotation psiAnnotation : psiMethod.getAnnotations()) {
            HttpMethod annotatedMethod = resolveHttpMethod(psiAnnotation);
            if (annotatedMethod == null) {
                continue;
            }
            RequestMappingAnnotationInfo annotationInfo = PsiAnnotationUtil.getAnnotationInfoByPojo(psiAnnotation, RequestMappingAnnotationInfo.class);
            HttpMethod httpMethod = annotatedMethod;
            if (httpMethod == HttpMethod.ALL) {
                List<String> methodList = annotationInfo.getMethod();
                httpMethod = CollectionUtils.isNotEmpty(methodList) ? HttpMethod.of(methodList.get(0)) : HttpMethod.ALL;
            }
            CommentInfo commentInfo = AnnotationHolder.getPsiMethodHolder(psiMethod).getCommentInfo();
            String description = commentInfo.getValue("");
            String location = psiClass.getName() + "#" + psiMethod.getName();
            List<String> params = annotationInfo.getParams();
            for (String methodPath : annotationInfo.getValueOrPath()) {
                methodPathList.add(new MethodPathInfo(psiMethod, httpMethod, methodPath, location, description, params));
            }
        }
        return methodPathList;
    }

    @Nullable
    private static HttpMethod resolveHttpMethod(@NotNull PsiAnnotation psiAnnotation) {
        String qname = psiAnnotation.getQualifiedName();
        return qname == null ? null : MAPPING_ANNOTATIONS.get(qname);
    }

    @NotNull
    private static String normalizeClassPath(@NotNull String classPath) {
        String normalized = classPath;
        if (!normalized.startsWith("/")) {
            normalized = "/".concat(normalized);
        }
        if (!normalized.endsWith("/")) {
            normalized = normalized.concat("/");
        }
        return normalized;
    }

    /**
     * 扫描过程中周期性检查, 既能响应用户取消, 也能让出挂起的写操作
     */
    private static void checkCanceled(@Nullable ProgressIndicator indicator) {
        if (indicator != null) {
            indicator.checkCanceled();
        }
        ProgressManager.checkCanceled();
    }

    private long getModificationStamp() {
        return PsiModificationTracker.getInstance(project).getModificationCount();
    }

    private static Map<String, HttpMethod> createMappingAnnotations() {
        Map<String, HttpMethod> mappings = new LinkedHashMap<>(8);
        mappings.put(AnnotationHolder.QNAME_OF_MAPPING, HttpMethod.ALL);
        mappings.put(AnnotationHolder.QNAME_OF_GET_MAPPING, HttpMethod.GET);
        mappings.put(AnnotationHolder.QNAME_OF_POST_MAPPING, HttpMethod.POST);
        mappings.put(AnnotationHolder.QNAME_OF_PUT_MAPPING, HttpMethod.PUT);
        mappings.put(AnnotationHolder.QNAME_OF_DELETE_MAPPING, HttpMethod.DELETE);
        return mappings;
    }

    private static final class CacheEntry {

        private final long stamp;
        private final List<ApiNavigationItem> items;

        private CacheEntry(long stamp, List<ApiNavigationItem> items) {
            this.stamp = stamp;
            this.items = items;
        }
    }

    private static final class FileScanResult {

        private final List<ApiNavigationItem> items;
        private final boolean cacheable;

        private FileScanResult(List<ApiNavigationItem> items, boolean cacheable) {
            this.items = items;
            this.cacheable = cacheable;
        }
    }

    private static final class RebuildResult {

        private final List<ApiNavigationItem> items;
        /**
         * 仅在内容有变化时非 null, 表示需要写入磁盘
         */
        private final ApiIndexSnapshot snapshot;

        private RebuildResult(List<ApiNavigationItem> items, ApiIndexSnapshot snapshot) {
            this.items = items;
            this.snapshot = snapshot;
        }
    }
}
