package com.chua.gateway.server.artifact;

import com.chua.common.support.network.download.Downloader;
import com.chua.gateway.server.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * artifact 本地优先解析器：local-override → cache → 下载。
 *
 * <p>查找流程（按优先级）：</p>
 * <ol>
 *   <li>{@code ~/.utils-support-gateway/local-override/{name}/{version}/{relativePath}}
 *       — 用户手动复制的优先</li>
 *   <li>{@code ~/.utils-support-gateway/cache/{name}/{version}/{relativePath}}
 *       — 已经下载缓存</li>
 *   <li>从 {@code downloadUrl} 下载到 cache 目录（由 common-starter {@link Downloader} 实现）</li>
 * </ol>
 *
 * <p>本地回退目录允许用户在网络不通时手动放置文件，
 * 是中国网络环境下推荐配置。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class LocalOverrideResolver {

    /**
     * 私有构造，禁止实例化。
     */
    private LocalOverrideResolver() {
    }

    /**
     * 展开 {@code ${user.home}} 占位符到真实路径。
     *
     * @param path 含占位符的路径
     * @return 展开后的路径字符串
     */
    public static String expandUserHome(String path) {
        if (path == null) {
            return null;
        }
        String userHome = System.getProperty("user.home", "");
        return path.replace("${user.home}", userHome);
    }

    /**
     * 仅查本地优先（不下载）。
     *
     * @param name        artifact 名
     * @param version     版本
     * @param targetPath  相对入口路径
     * @return 命中返回路径；未命中返回 {@code null}
     */
    public static Path resolveLocal(String name, String version, String targetPath) {
        Path localOverride = Paths.get(
                expandUserHome(GatewayProperties.localOverrideDir()), name, version, targetPath);
        if (Files.exists(localOverride)) {
            log.info("[gateway-server] 命中 local-override: {}", localOverride);
            return localOverride;
        }
        Path cache = Paths.get(
                expandUserHome(GatewayProperties.artifactDir()), name, version, targetPath);
        if (Files.exists(cache)) {
            log.info("[gateway-server] 命中 cache: {}", cache);
            return cache;
        }
        return null;
    }

    /**
     * 确保 artifact 就位（local-override → cache → 下载）。
     *
     * @param name         artifact 名（如 "guacd"）
     * @param version      版本（如 "1.5.5"）
     * @param targetPath   artifact 内入口路径（如 "bin/guacd"）
     * @param downloadUrl  下载源（如 "https://archive.apache.org/.../guacamole-server-1.5.5.tar.gz"）
     * @return 就位的 Path
     * @throws IOException 下载失败时抛出
     */
    public static Path ensure(String name, String version, String targetPath, String downloadUrl) throws IOException {
        Path local = resolveLocal(name, version, targetPath);
        if (local != null) {
            return local;
        }
        // 需要下载
        Path cacheDir = Paths.get(expandUserHome(GatewayProperties.artifactDir()));
        Path downloadTargetDir = cacheDir.resolve(name).resolve(version);
        Files.createDirectories(downloadTargetDir);
        String urlFileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
        log.info("[gateway-server] 下载 artifact: {} → {}", downloadUrl, downloadTargetDir.resolve(urlFileName));
        Path downloaded = Downloader.create()
                .url(downloadUrl)
                .target(downloadTargetDir)
                .filename(urlFileName)
                .showProgress(false)
                .autoExtract(true)
                .execute()
                .getFile();
        // 解压后查询目标路径
        Path target = downloadTargetDir.resolve(targetPath);
        if (!Files.exists(target)) {
            throw new IOException("下载完成但未找到目标路径: " + target
                    + "，可能压缩包结构与预期不符，downloadUrl=" + downloadUrl);
        }
        return target;
    }
}
