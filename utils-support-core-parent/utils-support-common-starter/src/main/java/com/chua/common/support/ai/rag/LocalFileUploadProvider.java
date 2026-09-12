package com.chua.common.support.ai.rag;

import com.chua.common.support.ai.rag.RagClient.UploadProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* 本地文件落盘的 UploadProvider 默认实现。
* <p>
* 将上传文件保存到本地目录（{@code uploadDir/files/}），
* 文件 ID 与文档 ID 对应，支持 {@link #upload}、{@link #read}、{@link #delete}。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class LocalFileUploadProvider implements UploadProvider {

    /** 子目录名 */
    private static final String FILES_SUBDIR = "files";

    /** 内存中已上传的文件内容缓存（docId -> data） */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /** 上传根目录 */
    private final Path baseDir;

    /**
    * 构造本地文件上传提供者。
    *
    * @param uploadDir 上传根目录路径
     */
    public LocalFileUploadProvider(String uploadDir) {
        this.baseDir = Path.of(uploadDir, FILES_SUBDIR);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("创建上传目录失败: " + e.getMessage(), e);
        }
        log.info("[LocalFileUploadProvider] 初始化完成, baseDir={}", baseDir);
    }

    @Override
    public String upload(String docId, String fileName, byte[] data) {
        cache.put(docId, data);
        try {
            Path target = baseDir.resolve(docId);
            Files.write(target, data);
            log.debug("[LocalFileUploadProvider] 上传成功: docId={}, fileName={}, size={}", docId, fileName, data.length);
        } catch (IOException e) {
            log.error("[LocalFileUploadProvider] 写入文件失败: docId={}", docId, e);
            throw new RuntimeException("写入文件失败: " + e.getMessage(), e);
        }
        return docId;
    }

    @Override
    public byte[] read(String fileId) {
        byte[] cached = cache.get(fileId);
        if (cached != null) {
            return cached;
        }
        try {
            Path file = baseDir.resolve(fileId);
            if (Files.exists(file)) {
                byte[] data = Files.readAllBytes(file);
                cache.put(fileId, data);
                return data;
            }
        } catch (IOException e) {
            log.warn("[LocalFileUploadProvider] 读取文件失败: fileId={}", fileId, e);
        }
        return null;
    }

    @Override
    public boolean delete(String fileId) {
        cache.remove(fileId);
        try {
            Path file = baseDir.resolve(fileId);
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[LocalFileUploadProvider] 删除文件失败: fileId={}", fileId, e);
            return false;
        }
    }
}
