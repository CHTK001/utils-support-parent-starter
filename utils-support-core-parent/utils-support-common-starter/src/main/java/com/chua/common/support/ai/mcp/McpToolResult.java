package com.chua.common.support.ai.mcp;

/**
 * MCP 工具调用结果
 *
 * <p>封装一次 MCP 工具调用的执行结果。
 *
 * @author CH
 * @since 2026/07/15
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public class McpToolResult {

    /** 是否成功 */
    /**
     * 是否成功
     */
    private final boolean success;

    /** 结果内容 */
    /**
     * 内容
     */
    private final Object content;

    /** 错误信息 */
    private final String errorMessage;

    public McpToolResult(boolean success, Object content, String errorMessage) {
        this.success = success;
        this.content = content;
        this.errorMessage = errorMessage;
    }

    public static McpToolResult success(Object content) {
        return new McpToolResult(true, content, null);
    }

    public static McpToolResult error(String errorMessage) {
        return new McpToolResult(false, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getContent() {
        return content;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}