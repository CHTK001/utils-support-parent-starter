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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub 仓库插件在线提供者。
 *
 * <p>通过 GitHub Git Trees API 从技能仓库发现包含 {@code plugin.json} 的插件目录，
 * 对齐 TokenTracker 插件仓库发现逻辑。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("github-plugins")
public class GitHubPluginOnlineProvider implements PluginOnlineProvider {

    /**
     * 日志
    */
    private static final Logger log = LoggerFactory.getLogger(GitHubPluginOnlineProvider.class);

    /**
     * GitHub API 基础
    */
    private static final String API_BASE = "https://api.github.com";

    /**
     * 默认插件仓库：{owner, name, branch}。
    */
    private static final String[][] DEFAULT_REPOS = {
            {"anthropics", "claude-code-plugins", "main"},
            {"anthropics", "skills", "main"},
            {"ComposioHQ", "awesome-claude-skills", "master"},
    };

    @Override
    public String name() {
        return "github-plugins";
    }

    @Override
    public List<PluginDefinition> search(String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        List<PluginDefinition> result = new ArrayList<>();
        for (String[] repo : DEFAULT_REPOS) {
            try {
                result.addAll(discoverRepoPlugins(repo[0], repo[1], repo[2], kw));
            } catch (Exception e) {
                log.warn("[github-plugins] 仓库扫描失败 {}: {}", repo[0] + "/" + repo[1], e.getMessage());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<PluginDefinition> discoverRepoPlugins(String owner, String name, String branch, String keyword)
            throws Exception {
        String url = API_BASE + "/repos/" + owner + "/" + name + "/git/trees/" + encode(branch) + "?recursive=1";
        ClientResponse response = HttpClientFactory.of(url)
                .header("Accept", "application/vnd.github+json")
                .get();
        if (!response.isSuccess()) {
            throw new RuntimeException("HTTP " + response.getStatusCode());
        }
        Map<String, Object> raw = Json.fromJson(response.getBodyString());
        List<Map<String, Object>> tree = (List<Map<String, Object>>) raw.get("tree");
        List<PluginDefinition> result = new ArrayList<>();
        if (tree == null) {
            return result;
        }
        for (Map<String, Object> entry : tree) {
            Object type = entry.get("type");
            Object path = entry.get("path");
            if (!"blob".equals(type) || path == null) {
                continue;
            }
            String filePath = String.valueOf(path);
            if (!filePath.toLowerCase(java.util.Locale.ROOT).matches(".*plugin\\.json$")) {
                continue;
            }
            String dir = filePath.replaceFirst("(?i)/?plugin\\.json$", "");
            int slash = dir.lastIndexOf('/');
            String pluginName = slash >= 0 ? dir.substring(slash + 1) : dir;
            if (pluginName.isEmpty() || pluginName.startsWith(".")) {
                continue;
            }
            if (!keyword.isEmpty() && !pluginName.toLowerCase(java.util.Locale.ROOT).contains(keyword)) {
                continue;
            }
            String id = owner + "/" + name + ":" + dir;
            String location = "https://github.com/" + owner + "/" + name + "/blob/" + branch + "/" + filePath;
            result.add(new PluginDefinition(id, pluginName, "GitHub 仓库插件: " + dir,
                    owner, "", location, name()));
        }
        return result;
    }

    /**
     * 编码。
     *
     * @param value 值，不允许为 null
     * @return 结果字符串
     */
    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    @Override
    public boolean install(String clientId, String pluginId) {
        log.info("GitHub 插件安装请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }

    @Override
    public boolean uninstall(String clientId, String pluginId) {
        log.info("GitHub 插件卸载请求: clientId={}, pluginId={}", clientId, pluginId);
        return true;
    }
}