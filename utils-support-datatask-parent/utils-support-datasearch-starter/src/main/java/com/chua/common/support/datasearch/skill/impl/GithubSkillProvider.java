package com.chua.common.support.datasearch.skill.impl;

import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.ai.skill.SkillArgumentSchema;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.ai.skill.SkillResult;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GitHub 技能仓库发现基础提供器。
 *
 * <p>对齐 TokenTracker {@code skills-manager.js} 的 discoverRepoSkills：从默认
 * 技能仓库（anthropics/skills、ComposioHQ/awesome-claude-skills 等）通过
 * GitHub Git Trees API 递归枚举 {@code SKILL.md}，发现可安装技能。
 * API 地址：{@code https://api.github.com/repos/{owner}/{name}/git/trees/{branch}?recursive=1}</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class GithubSkillProvider {

    /** 日志 */
    protected static final Logger log = LoggerFactory.getLogger(GithubSkillProvider.class);

    /** 名称 */
    protected static final String NAME = "github";
    /** 前缀 */
    protected static final String PREFIX = "";

    /** 默认技能仓库（对齐 TokenTracker DEFAULT_REPOS）。 */
    protected static final String[][] DEFAULT_REPOS = {
            {"anthropics", "skills", "main"},
            {"ComposioHQ", "awesome-claude-skills", "master"},
            {"cexll", "myclaude", "master"},
            {"JimLiu", "baoyu-skills", "main"},
    };

    /** GitHub API 基础。 */
    protected static final String API_BASE = "https://api.github.com";

    /**
     * 获取提供者名称。
     *
     * @return 名称
     */
    public String name() {
        return NAME;
    }

    /**
     * 安装（远程仓库技能，记录日志）。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 安装结果
     */
    public boolean install(String clientId, String skillId) {
        log.info("GitHub 技能安装请求: clientId={}, skillId={}", clientId, skillId);
        return true;
    }

    /**
     * 卸载。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 卸载结果
     */
    public boolean uninstall(String clientId, String skillId) {
        log.info("GitHub 技能卸载请求: clientId={}, skillId={}", clientId, skillId);
        return true;
    }

    /**
     * 获取 MCP 工具描述符列表。
     *
     * @return 工具描述符
     */
    public static List<McpToolDescriptor> toolDescriptors() {
        return List.of(
                new McpToolDescriptor(PREFIX + "discover",
                        "从 GitHub 技能仓库（anthropics/skills 等）发现 AI 技能",
                        Map.of("type", "object", "properties", Map.of(
                                "keyword", Map.of("type", "string", "description", "可选过滤关键词")),
                                "required", List.of()))
        );
    }

    /**
     * 构建发现 SkillDefinition。
     *
     * @return 发现技能
     */
    protected SkillDefinition discoverSkill() {
        return new SkillDefinition(
                PREFIX + "discover",
                "从 GitHub 技能仓库（anthropics/skills、awesome-claude-skills 等）发现 AI 技能",
                List.of(new SkillArgumentSchema("keyword", "可选过滤关键词", "string", false, null)),
                args -> toSkillResult(handleDiscover(args))
        );
    }

    /**
     * 处理发现工具调用。
     *
     * @param args 参数
     * @return 发现结果
     */
    protected McpToolResult handleDiscover(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        try {
            Map<String, Object> apiResult = discoverSkills(keyword);
            return McpToolResult.success(apiResult);
        } catch (Exception e) {
            log.error("GitHub 技能发现失败: keyword={}", keyword, e);
            return McpToolResult.error("API 调用失败: " + e.getMessage());
        }
    }

    /**
     * 遍历默认仓库递归获取 SKILL.md 技能。
     *
     * @param keyword 过滤关键词（可空）
     * @return 发现结果
     * @throws Exception 传输或解析异常
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> discoverSkills(String keyword) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        for (String[] repo : DEFAULT_REPOS) {
            String owner = repo[0];
            String name = repo[1];
            String branch = repo[2];
            List<String> paths = fetchTreePaths(owner, name, branch);
            for (String path : paths) {
                if (!path.toLowerCase(Locale.ROOT).matches("(^|/)skill\\.md$")) {
                    continue;
                }
                String directory = path.replaceAll("(?i)(^|/)skill\\.md$", "");
                if (directory.isEmpty()) {
                    directory = name;
                }
                String installName = directory;
                int slash = directory.lastIndexOf('/');
                if (slash >= 0) {
                    installName = directory.substring(slash + 1);
                }
                if (installName.isEmpty() || installName.startsWith(".")) {
                    continue;
                }
                if (!kw.isEmpty() && !(installName.toLowerCase(Locale.ROOT).contains(kw)
                        || directory.toLowerCase(Locale.ROOT).contains(kw))) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", owner + "/" + name + ":" + directory);
                item.put("name", installName);
                item.put("directory", directory);
                item.put("repoOwner", owner);
                item.put("repoName", name);
                item.put("repoBranch", branch);
                item.put("readmeUrl", "https://github.com/" + owner + "/" + name + "/blob/" + branch + "/" + path);
                items.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword == null ? "" : keyword);
        result.put("totalCount", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 获取指定仓库的分支递归文件树路径。
     *
     * @param owner  仓库 owner
     * @param name   仓库名
     * @param branch 分支
     * @return 文件路径列表
     */
    @SuppressWarnings("unchecked")
    protected List<String> fetchTreePaths(String owner, String name, String branch) throws Exception {
        String url = API_BASE + "/repos/" + owner + "/" + name + "/git/trees/" + encode(branch) + "?recursive=1";
        ClientResponse response = HttpClientFactory.of(url)
                .header("Accept", "application/vnd.github+json")
                .get();
        if (!response.isSuccess()) {
            throw new RuntimeException("HTTP " + response.getStatusCode());
        }
        Map<String, Object> raw = Json.fromJson(response.getBodyString());
        List<Map<String, Object>> tree = (List<Map<String, Object>>) raw.get("tree");
        if (tree == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (Map<String, Object> entry : tree) {
            Object type = entry.get("type");
            Object path = entry.get("path");
            if ("blob".equals(type) && path != null) {
                paths.add(String.valueOf(path));
            }
        }
        return paths;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    /**
     * 将 MCP 工具调用结果转换为 Skill 调用结果。
     *
     * @param mcpResult MCP 结果
     * @return Skill 结果
     */
    protected SkillResult toSkillResult(McpToolResult mcpResult) {
        if (mcpResult.isSuccess()) {
            return SkillResult.success(mcpResult.getContent());
        }
        return SkillResult.error(mcpResult.getErrorMessage());
    }
}