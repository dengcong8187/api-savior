package cn.gudqs7.plugins.search.index;

import lombok.Getter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口索引的磁盘快照, 按源文件分组保存:
 * 只要文件时间戳没变, 冷启动时就能直接读出接口列表, 无需加载任何 PSI
 *
 * @author wq
 */
@Getter
public final class ApiIndexSnapshot {

    private static final int FORMAT_VERSION = 1;

    private final Map<String, FileApiEntry> fileEntries;

    public ApiIndexSnapshot(Map<String, FileApiEntry> fileEntries) {
        this.fileEntries = fileEntries;
    }

    /**
     * 读取快照, 文件不存在或格式已过期时返回 null
     */
    public static ApiIndexSnapshot load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != FORMAT_VERSION) {
                return null;
            }
            int fileCount = in.readInt();
            Map<String, FileApiEntry> fileEntries = new HashMap<>(fileCount * 2);
            for (int i = 0; i < fileCount; i++) {
                FileApiEntry fileEntry = FileApiEntry.read(in);
                fileEntries.put(fileEntry.getFilePath(), fileEntry);
            }
            return new ApiIndexSnapshot(fileEntries);
        } catch (Exception e) {
            // 快照损坏或写入被中断, 当作没有缓存处理
            return null;
        }
    }

    public void store(Path path) throws IOException {
        Path parent = path.getParent();
        Files.createDirectories(parent);
        Path tmpFile = parent.resolve(path.getFileName().toString() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpFile)))) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(fileEntries.size());
            for (FileApiEntry fileEntry : fileEntries.values()) {
                fileEntry.write(out);
            }
        }
        try {
            Files.move(tmpFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmpFile, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public FileApiEntry find(String filePath) {
        return filePath == null ? null : fileEntries.get(filePath);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeStringList(DataOutputStream out, List<String> values) throws IOException {
        if (values == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    private static List<String> readStringList(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(readString(in));
        }
        return values;
    }

    /**
     * 单个源文件内的全部接口信息
     */
    @Getter
    public static final class FileApiEntry {

        private final String filePath;
        private final long fileStamp;
        private final List<MethodApiEntry> methods;

        public FileApiEntry(String filePath, long fileStamp, List<MethodApiEntry> methods) {
            this.filePath = filePath;
            this.fileStamp = fileStamp;
            this.methods = methods;
        }

        private void write(DataOutputStream out) throws IOException {
            writeString(out, filePath);
            out.writeLong(fileStamp);
            out.writeInt(methods.size());
            for (MethodApiEntry method : methods) {
                method.write(out);
            }
        }

        private static FileApiEntry read(DataInputStream in) throws IOException {
            String filePath = readString(in);
            long fileStamp = in.readLong();
            int methodCount = in.readInt();
            List<MethodApiEntry> methods = new ArrayList<>(methodCount);
            for (int i = 0; i < methodCount; i++) {
                methods.add(MethodApiEntry.read(in));
            }
            return new FileApiEntry(filePath, fileStamp, methods);
        }
    }

    /**
     * 单个接口方法信息, 不含 PSI 引用, 导航时按文件加偏移恢复
     */
    @Getter
    public static final class MethodApiEntry {

        private final String url;
        private final String httpMethod;
        private final String methodPath;
        private final String location;
        private final String methodDesc;
        private final List<String> params;
        private final int textOffset;

        public MethodApiEntry(String url, String httpMethod, String methodPath, String location,
                              String methodDesc, List<String> params, int textOffset) {
            this.url = url;
            this.httpMethod = httpMethod;
            this.methodPath = methodPath;
            this.location = location;
            this.methodDesc = methodDesc;
            this.params = params;
            this.textOffset = textOffset;
        }

        private void write(DataOutputStream out) throws IOException {
            writeString(out, url);
            writeString(out, httpMethod);
            writeString(out, methodPath);
            writeString(out, location);
            writeString(out, methodDesc);
            writeStringList(out, params);
            out.writeInt(textOffset);
        }

        private static MethodApiEntry read(DataInputStream in) throws IOException {
            String url = readString(in);
            String httpMethod = readString(in);
            String methodPath = readString(in);
            String location = readString(in);
            String methodDesc = readString(in);
            List<String> params = readStringList(in);
            int textOffset = in.readInt();
            return new MethodApiEntry(url, httpMethod, methodPath, location, methodDesc, params, textOffset);
        }
    }
}
