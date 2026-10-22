package cn.gudqs7.plugins.search.resolver;

import cn.gudqs7.plugins.common.enums.HttpMethod;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author wq
 * @date 2022/5/28
 */
@Getter
public class ApiNavigationItem {

    private final Project project;
    private final HttpMethod httpMethod;
    private final String url;
    private final MethodPathInfo methodPathInfo;
    /**
     * 来自磁盘快照的条目没有 PSI 方法, 只保留文件路径与偏移用于导航时恢复
     */
    private final String filePath;
    private final int textOffset;
    private final PsiMethod psiMethod;

    /**
     * 内存态: 直接持有已解析的方法
     */
    public ApiNavigationItem(@NotNull PsiMethod psiMethod, HttpMethod httpMethod, String url, MethodPathInfo methodPathInfo) {
        this.project = psiMethod.getProject();
        this.httpMethod = httpMethod;
        this.url = url;
        this.methodPathInfo = methodPathInfo;
        this.filePath = null;
        this.textOffset = psiMethod.getTextOffset();
        this.psiMethod = psiMethod;
    }

    /**
     * 磁盘态: 仅持定位信息
     */
    public ApiNavigationItem(Project project, HttpMethod httpMethod, String url, MethodPathInfo methodPathInfo,
                             @Nullable String filePath, int textOffset) {
        this.project = project;
        this.httpMethod = httpMethod;
        this.url = url;
        this.methodPathInfo = methodPathInfo;
        this.filePath = filePath;
        this.textOffset = textOffset;
        this.psiMethod = null;
    }

    /**
     * 获取待导航的元素, 磁盘态条目会按文件偏移恢复; 调用方需持有 read action
     */
    @Nullable
    public PsiElement getPsiElement() {
        if (psiMethod != null) {
            return psiMethod.isValid() ? psiMethod : null;
        }
        return resolvePsiMethod();
    }

    @Nullable
    private PsiMethod resolvePsiMethod() {
        if (project == null || filePath == null || textOffset < 0) {
            return null;
        }
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath);
        if (virtualFile == null || !virtualFile.isValid()) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
        if (psiFile == null) {
            return null;
        }
        PsiElement element = psiFile.findElementAt(textOffset);
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
    }

    @NotNull
    public String getRightText() {
        if (StringUtils.isNotBlank(methodPathInfo.getMethodDesc())) {
            return methodPathInfo.getMethodDesc() + " " + methodPathInfo.getLocation();
        } else {
            return methodPathInfo.getLocation();
        }
    }

    @Override
    public String toString() {
        return getRightText();
    }
}
