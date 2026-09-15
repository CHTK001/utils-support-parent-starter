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
import java.util.Map;

/**
 * skills.sh 技能市场搜索基础提供器。
 *
 * <p>通过 skills.sh 公开 API 搜索技能市场，封装 MCP 与 Skill 两类能力。
 * API 地址：{@code https://skills.sh/api/search}（TokenTracker 同源口径）。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class SkillShProvider {

    /** 日志 */
    protected static final Logger log = LoggerFactory.getLogger(SkillShProvider.class);

    /** 名称 */
    protected static final String NAME = "skills-sh";
    /** 前缀 */
    protected static final String PREFIX = "";
    /** API 基础 */
    protected static final String API_BASE = "https://skills.sh/api/search";

    /**
     * 获取提供者名称。
     *
     * @return 名称
     */
    public String name() {
        return NAME;
    }

    /**
     * 安装（MCP/Skill 通用），skills.sh 为远程市场，无需本地安装。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 安装结果
     */
    public boolean install(String clientId, String skillId) {
        log.info("Skills.sh 安装请求: clientId={}, skillOrToolId={}", clientId, skillId);
        return true;
    }

    /**
     * 卸载（MCP/Skill 通用）。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 卸载结果
     */
    public boolean uninstall(String clientId, String skillId) {
        log.info("Skills.sh 卸载请求: clientId={}, skillOrToolId={}", clientId, skillId);
        return true;
    }

    /**
     * 获取 MCP 工具描述符列表。
     *
     * @return 工具描述符
     */
    public static List<McpToolDescriptor> toolDescriptors() {
        return List.of(
                new McpToolDescriptor(PREFIX + "search", "搜索 skills.sh 技能市场，查找和发现 AI 技能",
                        Map.of("type", "object", "properties", Map.of(
                                "search", Map.of("type", "string", "description", "搜索关键词"),
                                "limit", Map.of("type", "integer", "description", "每页数量"),
                                "offset", Map.of("type", "integer", "description", "偏移量")),
                                "required", List.of("search")))
        );
    }

    /**
     * 构建搜索 SkillDefinition。
     *
     * @return 搜索技能
     */
    protected SkillDefinition searchSkill() {
        return new SkillDefinition(
                PREFIX + "search",
                "搜索 skills.sh 技能市场，查找和发现 AI 技能。支持按关键词搜索",
                List.of(
                        new SkillArgumentSchema("search", "搜索关键词", "string", true, null),
                        new SkillArgumentSchema("limit", "每页数量", "number", false, null),
                        new SkillArgumentSchema("offset", "偏移量", "number", false, null)
                ),
                args -> toSkillResult(handleSearch(args))
        );
    }

    /**
     * 处理搜索工具调用。
     *
     * @param args 参数
     * @return 搜索结果
     */
    protected McpToolResult handleSearch(Map<String, Object> args) {
        String search = (String) args.get("search");
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 20;
        int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 0;
        try {
            Map<String, Object> apiResult = callApi(search, limit, offset);
            return McpToolResult.success(apiResult);
        } catch (Exception e) {
            log.error("skills.sh API 调用失败: search={}", search, e);
            return McpToolResult.error("API 调用失败: " + e.getMessage());
        }
    }

    /**
     * 调用 skills.sh API。
     *
     * @param search 搜索关键词
     * @param limit  每页数量
     * @param offset 偏移量
     * @return 搜索结果
     * @throws Exception 传输或解析异常
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> callApi(String search, int limit, int offset) throws Exception {
        String url = API_BASE + "?q=" + encode(search)
                + "&limit=" + Math.max(1, Math.min(50, limit))
                + "&offset=" + Math.max(0, offset);
        ClientResponse response = HttpClientFactory.of(url).get();
        if (!response.isSuccess()) {
            throw new RuntimeException("HTTP " + response.getStatusCode());
        }
        Map<String, Object> rawResult = Json.fromJson(response.getBodyString());
        List<Map<String, Object>> rawSkills = (List<Map<String, Object>>) rawResult.get("skills");
        List<Map<String, Object>> items = new ArrayList<>();
        if (rawSkills != null) {
            for (Map<String, Object> skill : rawSkills) {
                Map<String, Object> item = new LinkedHashMap<>();
                String source = skill.get("source") == null ? "" : String.valueOf(skill.get("source"));
                int slash = source.indexOf('/');
                String repoOwner = slash > 0 ? source.substring(0, slash) : source;
                String repoName = slash > 0 ? source.substring(slash + 1) : source;
                item.put("key", skill.get("id"));
                item.put("name", skill.get("name"));
                item.put("skillId", skill.get("skillId"));
                item.put("repoOwner", repoOwner);
                item.put("repoName", repoName);
                item.put("readmeUrl", repoOwner.isBlank() || repoName.isBlank()
                        ? null : "https://github.com/" + repoOwner + "/" + repoName);
                item.put("installs", skill.get("installs"));
                items.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", rawResult.getOrDefault("query", search));
        result.put("totalCount", rawResult.getOrDefault("count", items.size()));
        result.put("items", items);
        return result;
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