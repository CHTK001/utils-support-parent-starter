package com.chua.common.support.ai.mcp;


/**
 * MCP 工具调用结果
 *
 * <p>封装一次 MCP 工具调用的执行结果。
 *
 * @author CH
 * @since 2026/07/15
 */
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

    /**
     * 创建 McpToolResult 实例
     * @param success success
     * @param content Object
     * @param errorMessage String
     */
    public McpToolResult(boolean success, Object content, String errorMessage) {
        this.success = success;
        this.content = content;
        this.errorMessage = errorMessage;
    }

    /**
     * Success
     * @param content 内容，不允许为 null
     * @return McpTool结果 对象
     */
    public static McpToolResult success(Object content) {
        return new McpToolResult(true, content, null);
    }

    /**
     * 记录错误
     * @param errorMessage error消息，不允许为 null
     * @return McpTool结果 对象
     */
    public static McpToolResult error(String errorMessage) {
        return new McpToolResult(false, null, errorMessage);
    }

    /**
     * 是否Success
     * @return 是否成功（true 表示成功）
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取Content
     * @return 对象 对象
     */
    public Object getContent() {
        return content;
    }

    /**
     * 获取记录错误Message
     * @return 结果字符串
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
