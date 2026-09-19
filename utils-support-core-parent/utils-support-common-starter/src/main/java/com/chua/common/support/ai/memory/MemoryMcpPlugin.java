package com.chua.common.support.ai.memory;

import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆体 MCP 插件
 *
 * <p>将记忆体操作暴露为 MCP 工具，LLM 可通过标准 MCP 协议主动管理记忆。
 * Agent 在 {@code initMemoryIfNeeded()} 时自动注册此插件，无需手动配置。
 *
 * <h3>注册的 MCP 工具</h3>
 * <pre>
 *   memory_save    — 保存一条记忆（LLM 可主动存储重要信息）
 *   memory_search  — 按关键词搜索记忆（LLM 可主动检索相关背景）
 *   memory_list    — 按类型列出记忆
 *   memory_delete  — 删除指定记忆
 *   memory_count   — 获取记忆总数
 * </pre>
 *
 * <h3>LLM 调用链</h3>
 * <pre>
 *   用户: "记住我喜欢用 Java"
 *     → LLM 判断需要保存记忆
 *     → LLM 输出: 调用 memory_save 工具
 *     → 框架检测到 MCP 工具调用
 *     → MemoryMcpPlugin.handleSave() → MemoryManager.save()
 *     → MemoryEntry 存储到工作间文件
 *
 *   用户: "我之前说过什么关于数据库的？"
 *     → LLM 判断需要检索记忆
 *     → LLM 输出: 调用 memory_search 工具
 *     → MemoryMcpPlugin.handleSearch() → MemoryManager.search("数据库")
 *     → 返回相关记忆 → LLM 基于记忆生成回复
 * </pre>
 *
 * @author CH
 * @since 2026/07/16
 */
@SuppressWarnings("unchecked")
public class MemoryMcpPlugin {

    /**
     * 工具名称前缀
    */
    private static final String PREFIX = "memory_";

    /**
     * 记忆管理器
    */
    private final MemoryManager manager;

    /**
     * 创建 MemoryMcpPlugin 实例
     * @param manager manager
     */
    public MemoryMcpPlugin(MemoryManager manager) {
        this.manager = manager;
    }

    /**
     * 注册记忆体工具到 MCP 管理器
     *
     * <p>将 5 个记忆操作注册为 MCP 工具，使 Agent 可通过工具调用管理记忆。
     *
     * @param mcpManager MCP 管理器
     */
    public void registerTo(McpManager mcpManager) {
        // memory_save
        mcpManager.register("memory", new com.chua.common.support.ai.mcp.McpClient() {
            /**
             * 是否已初始化
            */
            private boolean initialized = false;

            @Override
            /**
             * 初始化
            */
            public void init() { initialized = true; }

            @Override
            /**
             * ListTools
            */
            public List<McpToolDescriptor> listTools() {
                return List.of(
                    new McpToolDescriptor(PREFIX + "save",
                        "保存一条记忆到长期记忆库",
                        Map.of("type", "object",
                            "properties", Map.of(
                                "content", Map.of("type", "string", "description", "记忆内容"),
                                "type", Map.of("type", "string", "description", "记忆类型: fact/preference/decision/context"),
                                "importance", Map.of("type", "number", "description", "重要性 0.0-1.0"),
                                "tags", Map.of("type", "array", "items", Map.of("type", "string"), "description", "检索标签")
                            ),
                            "required", List.of("content"))),
                    new McpToolDescriptor(PREFIX + "search",
                        "按关键词搜索记忆库中的相关记忆",
                        Map.of("type", "object",
                            "properties", Map.of(
                                "keyword", Map.of("type", "string", "description", "搜索关键词"),
                                "limit", Map.of("type", "integer", "description", "最大返回数量", "default", 10)
                            ),
                            "required", List.of("keyword"))),
                    new McpToolDescriptor(PREFIX + "list",
                        "按类型列出记忆条目",
                        Map.of("type", "object",
                            "properties", Map.of(
                                "type", Map.of("type", "string", "description", "记忆类型: fact/preference/decision/context/raw/summary"),
                                "limit", Map.of("type", "integer", "description", "最大返回数量", "default", 20)
                            ),
                            "required", List.of("type"))),
                    new McpToolDescriptor(PREFIX + "delete",
                        "删除指定的记忆条目",
                        Map.of("type", "object",
                            "properties", Map.of(
                                "id", Map.of("type", "string", "description", "记忆 ID")
                            ),
                            "required", List.of("id"))),
                    new McpToolDescriptor(PREFIX + "count",
                        "获取记忆库中的记忆总数",
                        Map.of("type", "object", "properties", Map.of()))
                );
            }

            @Override
            /**
             * 调用Tool
            */
            public McpToolResult callTool(McpToolCall toolCall) {
                String toolName = toolCall.getToolName();
                Map<String, Object> args = toolCall.getArguments();
                return switch (toolName) {
                    case PREFIX + "save" -> handleSave(args);
                    case PREFIX + "search" -> handleSearch(args);
                    case PREFIX + "list" -> handleList(args);
                    case PREFIX + "delete" -> handleDelete(args);
                    case PREFIX + "count" -> handleCount();
                    default -> McpToolResult.error("未知工具: " + toolName);
                };
            }

            @Override
            /**
             * 是否Initialized
            */
            public boolean isInitialized() { return initialized; }

            @Override
            /**
             * 关闭
            */
            public void close() {}
        });
    }

    /**
     * 处理保存
     * @param args 参数，不允许为 null
     * @return McpTool结果 对象
     */
    private McpToolResult handleSave(Map<String, Object> args) {
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            return McpToolResult.error("content 不能为空");
        }
        String id = UUID.randomUUID().toString();
        MemoryEntry entry = MemoryEntry.builder()
                .id(id)
                .content(content)
                .type((String) args.getOrDefault("type", "fact"))
                .importance(toDouble(args.get("importance")))
                .tags(toTagList(args.get("tags")))
                .build();
        manager.save(entry);
        return McpToolResult.success(Map.of("success", true, "id", id));
    }

    /**
     * 处理搜索
     * @param args 参数，不允许为 null
     * @return McpTool结果 对象
     */
    private McpToolResult handleSearch(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        int limit = toInt(args.get("limit"), 10);
        List<MemoryEntry> results = manager.search(keyword, limit);
        return McpToolResult.success(results);
    }

    /**
     * 处理List
     * @param args 参数，不允许为 null
     * @return McpTool结果 对象
     */
    private McpToolResult handleList(Map<String, Object> args) {
        String type = (String) args.get("type");
        int limit = toInt(args.get("limit"), 20);
        List<MemoryEntry> results = manager.listByType(type, limit);
        return McpToolResult.success(results);
    }

    /**
     * 处理删除
     * @param args 参数，不允许为 null
     * @return McpTool结果 对象
     */
    private McpToolResult handleDelete(Map<String, Object> args) {
        String id = (String) args.get("id");
        boolean deleted = manager.delete(id);
        return McpToolResult.success(Map.of("deleted", deleted));
    }

    /**
     * 处理计算数量
     * @return McpTool结果 对象
     */
    private McpToolResult handleCount() {
        return McpToolResult.success(Map.of("count", manager.count()));
    }

    /**
     * ToDouble
     * @param obj 对象，不允许为 null
     * @return 结果数值
     */
    private double toDouble(Object obj) {
        if (obj instanceof Number n) {
            return n.doubleValue();
        }
        return 0.5;
    }

    /**
     * ToInt
     * @param obj 对象，不允许为 null
     * @param defaultVal 方法入参 defaultVal
     * @return 结果数值
     */
    private int toInt(Object obj, int defaultVal) {
        if (obj instanceof Number n) {
            return n.intValue();
        }
        return defaultVal;
    }

    /**
     * ToTagList
     * @param obj 对象，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private List<String> toTagList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
