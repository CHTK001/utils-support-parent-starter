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
 * 将上传文件保存到本地目录（{@code uploadDir/files/}），
 * 文件 ID 与文档 ID 对应，支持 {@link #upload}、{@link #read}、{@link #delete}。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LocalFileUploadProvider implements UploadProvider {

    /**
     * 子目录名
     */
    private static final String FILES_SUBDIR = "files";

    /**
     * 内存中已上传的文件内容缓存（docId -> data）
     */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /**
     * 上传根目录
     */
    private final Path baseDir;

    /**
     * 构造本地文件上传提供者。
     *
     * @param uploadDir 上传根目录路径
     */
    public LocalFileUploadProvider(String uploadDir) {
        // 必须转绝对路径并归一化：baseDir 若保留前导 "./"，后续 startsWith 的分段比较
        // 会因为 "." 段对不上而把合法 docId 误判为越界
        this.baseDir = Path.of(uploadDir, FILES_SUBDIR).toAbsolutePath().normalize();
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
            Path target = resolveInside(docId);
            // docId 允许含 '/'（形如 <库ID>/<库内相对路径>），父目录不一定存在，必须先建
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, data);
            log.debug("[LocalFileUploadProvider] 上传成功: docId={}, fileName={}, size={}", docId, fileName, data.length);
        } catch (IOException e) {
            log.error("[LocalFileUploadProvider] 写入文件失败: docId={}", docId, e);
            throw new RuntimeException("写入文件失败: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.error("[LocalFileUploadProvider] docId 非法: docId={}", docId, e);
            throw new RuntimeException("docId 非法: " + e.getMessage(), e);
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
            Path file = resolveInside(fileId);
            if (Files.exists(file)) {
                byte[] data = Files.readAllBytes(file);
                cache.put(fileId, data);
                return data;
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("[LocalFileUploadProvider] 读取文件失败: fileId={}", fileId, e);
        }
        return null;
    }

    @Override
    public boolean delete(String fileId) {
        cache.remove(fileId);
        try {
            return Files.deleteIfExists(resolveInside(fileId));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("[LocalFileUploadProvider] 删除文件失败: fileId={}", fileId, e);
            return false;
        }
    }

    /**
     * 把文档 ID 解析成 {@code baseDir} 之内的实际落盘路径。
     *
     * <p>文档 ID 是逻辑标识，允许包含 {@code '/'}（例如
     * {@code <库ID>/<库内相对路径>}），因此解析结果可能是多级路径。
     * 这里同时做两件事：</p>
     * <ol>
     *   <li>归一化后校验结果仍在 {@code baseDir} 之内，
     *       拒绝 {@code ../} 穿越、绝对路径等越界输入；</li>
     *   <li>把 Windows 的反斜杠一并视为分隔符，
     *       避免同一份文档 ID 在不同操作系统上落盘位置不一致。</li>
     * </ol>
     *
     * @param fileId 文档 ID（逻辑标识，可含 {@code '/'}）
     * @return {@code baseDir} 之内的落盘路径
     * @throws IllegalArgumentException ID 为空、为绝对路径，或归一化后越出 {@code baseDir}
     */
    private Path resolveInside(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("文件 ID 不能为空");
        }
        String normalized = fileId.replace('\\', '/');
        Path resolved = baseDir.resolve(normalized).normalize();
        if (!resolved.startsWith(baseDir) || resolved.equals(baseDir)) {
            throw new IllegalArgumentException("文件 ID 越界: " + fileId);
        }
        return resolved;
    }
}
