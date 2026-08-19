package com.chua.filestorage.support.flash;

import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 闪图（一次性预览/下载）Token 服务。
 *
 * <p>使用 0 字节 marker 文件存储 Token。
 * 文件路径 = {@code <flashDir>/<token>}。</p>
 *
 * <p>生命周期：
 * <ol>
 *   <li>创建：生成 UUID 并写 0 字节文件到 flashDir</li>
 *   <li>使用：消费时校验文件存在，读取 token 后删除 marker 文件</li>
 *   <li>过期：后台线程定期清理超过 {@code flashExpireSeconds} 的 marker 文件</li>
 * </ol>
 * </p>
 *
 * @author CH
 * @since 2024/12/28
 */
@Slf4j
public class FlashTokenService {

    /** Flash目录 */
    private final Path flashDir;
    /** Expire秒 */
    private final long expireSeconds;

    /**
     * 创建 FlashTokenService 实例
     * @param flashDir flashDir
     * @param long long
     */
    public FlashTokenService(Path flashDir, long expireSeconds) {
        this.flashDir = flashDir;
        this.expireSeconds = expireSeconds > 0 ? expireSeconds : 600;
        try {
            Files.createDirectories(this.flashDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to init flash dir: " + flashDir, e);
        }
    }

    /**
     * 创建一个闪图 Token，返回生成的 token 字符串。
     *
     * @return token
     */
    public String createToken() throws IOException {
        String token = UUID.randomUUID().toString().replace("-", "");
        Path marker = flashDir.resolve(token);
        Files.writeString(marker, "", StandardOpenOption.CREATE_NEW);
        log.debug("[FlashToken] 创建 token: {}", token);
        return token;
    }

    /**
     * 验证 token 是否存在且未过期。
     *
     * @param token token 字符串
     * @return true 表示有效
     */
    public boolean validateToken(String token) {
        if (StringUtils.isEmpty(token)) {
            return false;
        }
        Path marker = flashDir.resolve(token);
        if (!Files.exists(marker)) {
            return false;
        }
        try {
            long lastModified = Files.getLastModifiedTime(marker).toMillis();
            return (System.currentTimeMillis() - lastModified) < expireSeconds * 1000;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 消费 token：验证并删除 marker 文件。
     *
     * @param token token 字符串
     * @return true 表示消费成功（删除成功）
     */
    public boolean consumeToken(String token) {
        if (StringUtils.isEmpty(token)) {
            return false;
        }
        Path marker = flashDir.resolve(token);
        try {
            if (Files.deleteIfExists(marker)) {
                log.debug("[FlashToken] 消费 token: {}", token);
                return true;
            }
        } catch (IOException e) {
            log.warn("[FlashToken] 删除 marker 文件失败: {}", token, e);
        }
        return false;
    }

    /**
     * 清理所有过期的 marker 文件。
     */
    public void cleanExpired() {
        long cutoff = System.currentTimeMillis() - expireSeconds * 1000;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(flashDir)) {
            for (Path marker : stream) {
                try {
                    if (Files.size(marker) == 0 && Files.getLastModifiedTime(marker).toMillis() < cutoff) {
                        Files.deleteIfExists(marker);
                        log.debug("[FlashToken] 清理过期 token: {}", marker.getFileName());
                    }
                } catch (IOException e) {
                    log.warn("[FlashToken] 清理 token 文件失败: {}", marker, e);
                }
            }
        } catch (IOException e) {
            log.warn("[FlashToken] 扫描闪图目录失败: {}", flashDir, e);
        }
    }
}
