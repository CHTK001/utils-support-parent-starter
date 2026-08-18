package com.chua.common.support.datasearch.skill.impl;

import com.chua.common.support.ai.mcp.McpClient;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SkillsMP 技能市场搜索基础提供器，封装 MCP 和 Skill 的公共能力。
 *
 * <p>通过 SkillsMP 公开 API 搜索技能市场中的 AI 技能，支持按关键词、页码、排序方式查询。
 * API 地址：{@code https://skillsmp.com/api/skills}
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SkillsmpProvider {

    /** 日志 */
    protected static final Logger log = LoggerFactory.getLogger(SkillsmpProvider.class);

    /** 名称 */
    protected static final String NAME = "skillsmp";
    /** Prefix */
    protected static final String PREFIX = "";
    /** Api_base */
    protected static final String API_BASE = "https://skillsmp.com/api/skills";

    /**
     * 获取提供者名称
     */
    public String name() {
        return NAME;
    }

    /**
     * 安装（MCP/Skill 通用），SkillsMP 为远程服务，无需本地安装
     */
    public boolean install(String clientId, String skillId) {
        log.info("SkillsMP 安装请求: clientId={}, skillOrToolId={}", clientId, skillId);
        return true;
    }

    /**
     * 卸载（MCP/Skill 通用）
     */
    public boolean uninstall(String clientId, String skillId) {
        log.info("SkillsMP 卸载请求: clientId={}, skillOrToolId={}", clientId, skillId);
        return true;
    }

    /**
     * 获取 MCP 工具描述符列表
     */
    public static List<McpToolDescriptor> toolDescriptors() {
        return List.of(
                new McpToolDescriptor(PREFIX + "search", "搜索 SkillsMP 技能市场，查找和发现 AI 技能",
                        Map.of("type", "object", "properties", Map.of(
                                "search", Map.of("type", "string", "description", "搜索关键词"),
                                "page", Map.of("type", "integer", "description", "页码"),
                                "limit", Map.of("type", "integer", "description", "每页数量"),
                                "sortBy", Map.of("type", "string", "description", "排序方式(stars/forks/updated)")),
                                "required", List.of("search")))
        );
    }

    /**
     * 构建搜索 SkillDefinition
     */
    protected SkillDefinition searchSkill() {
        return new SkillDefinition(
                PREFIX + "search",
                "搜索 SkillsMP 技能市场，查找和发现 AI 技能。支持按关键词搜索，按 stars/forks/updated 排序",
                List.of(
                        new SkillArgumentSchema("search", "搜索关键词", "string", true, null),
                        new SkillArgumentSchema("page", "页码", "number", false, null),
                        new SkillArgumentSchema("limit", "每页数量", "number", false, null),
                        new SkillArgumentSchema("sortBy", "排序方式", "string", false, List.of("stars", "forks", "updated"))
                ),
                args -> toSkillResult(handleSearch(args))
        );
    }

    /**
     * 处理搜索工具调用
     */
    protected McpToolResult handleSearch(Map<String, Object> args) {
        String search = (String) args.get("search");
        int page = args.containsKey("page") ? ((Number) args.get("page")).intValue() : 1;
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 12;
        String sortBy = (String) args.getOrDefault("sortBy", "stars");

        try {
            Map<String, Object> apiResult = callApi(search, page, limit, sortBy);
            return McpToolResult.success(apiResult);
        } catch (Exception e) {
            log.error("skillsmp API 调用失败: search={}", search, e);
            return McpToolResult.error("API 调用失败: " + e.getMessage());
        }
    }

    /**
     * 调用 SkillsMP API
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> callApi(String search, int page, int limit, String sortBy) throws Exception {
        String url = API_BASE + "?page=" + page + "&limit=" + limit + "&sortBy=" + sortBy
                + "&search=" + URLEncoder.encode(search, StandardCharsets.UTF_8);

        ClientResponse response = HttpClientFactory.of(url).get();
        if (!response.isSuccess()) {
            throw new RuntimeException("HTTP " + response.getStatusCode());
        }

        Map<String, Object> rawResult = Json.fromJson(response.getBodyString());
        List<Map<String, Object>> rawSkills = (List<Map<String, Object>>) rawResult.get("skills");
        Map<String, Object> pagination = (Map<String, Object>) rawResult.get("pagination");

        List<Map<String, Object>> items = new ArrayList<>();
        if (rawSkills != null) {
            for (Map<String, Object> skill : rawSkills) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", skill.get("id"));
                item.put("name", skill.get("name"));
                item.put("author", skill.get("author"));
                item.put("authorAvatar", skill.get("authorAvatar"));
                item.put("description", skill.get("description"));
                item.put("stars", skill.get("stars"));
                item.put("forks", skill.get("forks"));
                item.put("contentLanguage", skill.get("contentLanguage"));
                item.put("githubUrl", skill.get("githubUrl"));
                items.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        if (pagination != null) {
            result.put("pagination", pagination);
        }
        result.put("search", search);
        return result;
    }

    /**
     * 将 MCP 工具调用结果转换为 Skill 调用结果
     */
    protected SkillResult toSkillResult(McpToolResult mcpResult) {
        if (mcpResult.isSuccess()) {
            return SkillResult.success(mcpResult.getContent());
        }
        return SkillResult.error(mcpResult.getErrorMessage());
    }

    /**
     * SkillsMP MCP 客户端实现
     */
    protected class SkillsmpMcpClient implements McpClient {
        private volatile boolean initialized = false;

        @Override
        public void init() {
            initialized = true;
            log.info("skillsmp MCP 客户端已初始化");
        }

        @Override
        public List<McpToolDescriptor> listTools() {
            return toolDescriptors();
        }

        @Override
        public McpToolResult callTool(McpToolCall toolCall) {
            try {
                if ((PREFIX + "search").equals(toolCall.getToolName())) {
                    return handleSearch(toolCall.getArguments());
                }
                return McpToolResult.error("未知工具: " + toolCall.getToolName());
            } catch (Exception e) {
                log.error("skillsmp 工具调用异常", e);
                return McpToolResult.error("调用异常: " + e.getMessage());
            }
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }
    }
}