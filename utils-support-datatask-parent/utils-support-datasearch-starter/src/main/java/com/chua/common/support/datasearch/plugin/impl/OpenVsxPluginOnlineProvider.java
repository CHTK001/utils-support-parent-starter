package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.datasearch.plugin.model.PluginDefinition;
import com.chua.common.support.datasearch.plugin.spi.PluginOnlineProvider;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Open VSX 在线插件市场提供者。
 *
 * <p>通过 Open VSX 公开搜索 API 查询 VS Code 扩展：
 * {@code https://open-vsx.org/api/-/search?query=...&size=...&offset=...}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("open-vsx")
public class OpenVsxPluginOnlineProvider implements PluginOnlineProvider {

    /**
     * 日志
    */
    private static final Logger log = LoggerFactory.getLogger(OpenVsxPluginOnlineProvider.class);

    /**
     * API 基础
    */
    private static final String API_BASE = "https://open-vsx.org/api/-/search";

    @Override
    public String name() {
        return "open-vsx";
    }

    @Override
    public List<PluginDefinition> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        try {
            String url = API_BASE + "?query=" + encode(keyword) + "&size=20&offset=0";
            ClientResponse response = HttpClientFactory.of(url).get();
            if (!response.isSuccess()) {
                throw new RuntimeException("HTTP " + response.getStatusCode());
            }
            Map<String, Object> raw = Json.fromJson(response.getBodyString());
            List<Map<String, Object>> extensions =
                    (List<Map<String, Object>>) raw.get("extensions");
            List<PluginDefinition> result = new ArrayList<>();
            if (extensions != null) {
                for (Map<String, Object> ext : extensions) {
                    Map<String, Object> fields = (Map<String, Object>) ext.get("extension");
                    if (fields == null) {
                        fields = ext;
                    }
                    result.add(toPlugin(fields));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[open-vsx] 搜索失败: keyword={}, err={}", keyword, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private PluginDefinition toPlugin(Map<String, Object> fields) {
        String name = str(fields.get("name"));
        String publisher = str(fields.get("publisher"));
        String displayName = str(fields.get("displayName"));
        String description = str(fields.get("description"));
        String version = str(fields.get("version"));
        Object latest = fields.get("latestVersion");
        if (latest instanceof Map) {
            Map<String, Object> latestMap = (Map<String, Object>) latest;
            if (version.isBlank()) {
                version = str(latestMap.get("version"));
            }
            if (description.isBlank()) {
                description = str(latestMap.get("description"));
            }
        }
        String id = (publisher.isBlank() ? "" : publisher + ".") + name;
        String display = displayName.isBlank() ? id : displayName;
        String url = "https://open-vsx.org/extension/"
                + (publisher.isBlank() ? name : publisher + "/" + name);
        return new PluginDefinition(id, display, description, publisher, version, url, name());
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    @Override
    public boolean install(String clientId, String pluginId) {
        log.info("Open VSX 安装请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }

    @Override
    public boolean uninstall(String clientId, String pluginId) {
        log.info("Open VSX 卸载请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }
}