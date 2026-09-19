package com.chua.webdav.support.config;

import com.chua.common.support.config.loader.AbstractConfigSaveOrLoader;
import com.chua.common.support.config.loader.ConfigSaveLoadSetting;
import com.chua.common.support.config.loader.ConfigSaveResult;
import com.chua.common.support.spi.annotations.Spi;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * 基于 webdav 的配置保存/加载器。
 *
 * <p>使用 Sardine WebDAV 客户端将配置数据持久化到远程 WebDAV 服务器。
 * 作为 {@link ConfigSaveOrLoader} 的 SPI 实现，支持配置的保存、加载和删除操作。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("webdav")
public class WebdavConfigSaveOrLoader extends AbstractConfigSaveOrLoader {

    /**
     * sardine
     */
    private final Sardine sardine;
    /**
     * 基础地址
     */
    private final String baseUrl;

    /**
     * 创建 webdav配置保存或加载 实例
     * @param setting setting
     */
    public WebdavConfigSaveOrLoader(ConfigSaveLoadSetting setting) {
        super(setting != null ? setting : ConfigSaveLoadSetting.builder().build());
        String username = this.setting.getUsername() != null ? this.setting.getUsername() : "";
        String password = this.setting.getPassword() != null ? this.setting.getPassword() : "";
        this.sardine = SardineFactory.begin(username, password);
        String endpoint = this.setting.getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("WebDAV 配置必须指定 endpoint");
        }
        String url = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        String basePath = this.setting.getBasePath() != null ? this.setting.getBasePath() : "";
        this.baseUrl = url + (basePath.endsWith("/") ? basePath : basePath + "/");
    }

    /**
     * 完整url
     *
     * @param key 键
     * @return 完整url的结果
     */
    private String fullUrl(String key) {
        return baseUrl + normalizeKey(key);
    }

    /**
     * ensure父路径
     *
     * @param key 键
     */
    private void ensureParentPath(String key) throws IOException {
        String normalized = normalizeKey(key);
        if (!normalized.contains("/")) {
            return;
        }
        String parent = normalized.substring(0, normalized.lastIndexOf('/'));
        String[] parts = parent.split("/");
        StringBuilder path = new StringBuilder(baseUrl);
        for (String part : parts) {
            path.append(part).append("/");
            if (!sardine.exists(path.toString())) {
                sardine.createDirectory(path.toString());
            }
        }
    }

    @Override
    /** 保存Bytes */
    public ConfigSaveResult saveBytes(String key, byte[] content) {
        try {
            ensureParentPath(key);
            sardine.put(fullUrl(key), content, setting.getContentType());
            return success(key, fullUrl(key), content.length);
        } catch (Exception e) {
            log.error("WebDAV 保存配置失败: key={}", key, e);
            return failure(key, e.getMessage());
        }
    }

    @Override
    /** 加载Bytes */
    public Optional<byte[]> loadBytes(String key) {
        try {
            InputStream is = sardine.get(fullUrl(key));
            byte[] bytes = is.readAllBytes();
            is.close();
            return Optional.of(bytes);
        } catch (Exception e) {
            log.debug("WebDAV 加载配置失败: key={}", key);
            return Optional.empty();
        }
    }

    @Override
    /** 删除 */
    public boolean delete(String key) {
        try {
            sardine.delete(fullUrl(key));
            return true;
        } catch (Exception e) {
            log.error("WebDAV 删除配置失败: key={}", key, e);
            return false;
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        try {
            sardine.shutdown();
        } catch (Exception ignored) {
        }
    }
}
