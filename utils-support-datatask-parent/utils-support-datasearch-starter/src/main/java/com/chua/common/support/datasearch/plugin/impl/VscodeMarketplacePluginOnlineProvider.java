package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.datasearch.plugin.model.PluginDefinition;
import com.chua.common.support.datasearch.plugin.spi.PluginOnlineProvider;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VS Code Marketplace 在线插件市场提供者。
 *
 * <p>通过 VS Code Marketplace 搜索 API（POST
 * {@code https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery}）
 * 查询扩展。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("vscode-marketplace")
public class VscodeMarketplacePluginOnlineProvider implements PluginOnlineProvider {

    /**
     * 日志
    */
    private static final Logger log = LoggerFactory.getLogger(VscodeMarketplacePluginOnlineProvider.class);

    /**
     * 搜索 API
    */
    private static final String SEARCH_API =
            "https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery";

    @Override
    public String name() {
        return "vscode-marketplace";
    }

    @Override
    public List<PluginDefinition> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        try {
            String body = "{\"filters\":[{\"criteria\":[{\"filterType\":10,\"value\":\"" + keyword + "\"}],"
                    + "\"pageNumber\":1,\"pageSize\":20,\"sortBy\":0,\"sortOrder\":0}],"
                    + "\"flags\":950}";
            ClientResponse response = HttpClientFactory.of(SEARCH_API)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json;api-version=3.0-preview.1")
                    .body(body)
                    .post();
            if (!response.isSuccess()) {
                throw new RuntimeException("HTTP " + response.getStatusCode());
            }
            Map<String, Object> raw = Json.fromJson(response.getBodyString());
            List<Map<String, Object>> results =
                    (List<Map<String, Object>>) raw.get("results");
            List<PluginDefinition> result = new ArrayList<>();
            if (results != null) {
                for (Map<String, Object> page : results) {
                    List<Object> extensions = (List<Object>) page.get("extensions");
                    if (extensions == null) {
                        continue;
                    }
                    for (Object ext : extensions) {
                        if (ext instanceof Map) {
                            PluginDefinition def = toPlugin((Map<String, Object>) ext);
                            if (def != null) {
                                result.add(def);
                            }
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[vscode-marketplace] 搜索失败: keyword={}, err={}", keyword, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private PluginDefinition toPlugin(Map<String, Object> raw) {
        Map<String, Object> metadata = (Map<String, Object>) raw.get("extension");
        if (metadata == null) {
            return null;
        }
        String displayName = str(metadata.get("displayName"));
        String publisher = str(metadata.get("publisher"));
        String extensionName = str(metadata.get("extensionName"));
        String version = str(metadata.get("version"));
        String description = str(metadata.get("shortDescription"));
        String id = (publisher.isBlank() ? "" : publisher + ".") + extensionName;
        String url = "https://marketplace.visualstudio.com/items?itemName="
                + (publisher.isBlank() ? id : publisher + "." + extensionName);
        if (displayName.isBlank()) {
            displayName = id;
        }
        return new PluginDefinition(id, displayName, description, publisher, version, url, name());
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @Override
    public boolean install(String clientId, String pluginId) {
        log.info("VS Code Marketplace 安装请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }

    @Override
    public boolean uninstall(String clientId, String pluginId) {
        log.info("VS Code Marketplace 卸载请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }
}