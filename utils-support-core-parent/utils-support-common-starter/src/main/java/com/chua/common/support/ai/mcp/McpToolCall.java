package com.chua.common.support.ai.mcp;

import java.util.Map;

/**
 * MCP 工具调用请求
 *
 * <p>封装一次 MCP 工具调用的参数，包含工具名称和参数字典。
 *
 * @author CH
 * @since 2026/07/15
 */
public class McpToolCall {

    /** 工具名称 */
    /** Tool名称 */
    private final String toolName;

    /** 调用参数 */
    private final Map<String, Object> arguments;

    public McpToolCall(String toolName, Map<String, Object> arguments) {
        this.toolName = toolName;
        this.arguments = arguments;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
