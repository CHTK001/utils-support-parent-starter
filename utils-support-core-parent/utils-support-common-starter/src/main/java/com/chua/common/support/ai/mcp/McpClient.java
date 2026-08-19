package com.chua.common.support.ai.mcp;

import java.util.List;

/**
 * MCP 客户端接口
 *
 * <p>封装与 MCP（Model Context Protocol）服务端通信的能力，提供工具列表查询和工具调用功能。
 * 实现类通过 SPI 机制注册。
 *
 * @author CH
 * @since 2026/07/15
 */
public interface McpClient extends AutoCloseable {

    /**
     * 初始化客户端，建立与 MCP 服务端的连接
     */
    void init();

    /**
     * 获取服务端提供的所有工具列表
     *
     * @return 工具描述符列表
     */
    List<McpToolDescriptor> listTools();

    /**
     * 调用指定的 MCP 工具
     *
     * @param toolCall 工具调用请求
     * @return 工具调用结果
     */
    McpToolResult callTool(McpToolCall toolCall);

    /**
     * 客户端是否已初始化
     *
     * @return 是否已初始化
     */
    boolean isInitialized();

    @Override
    /** 关闭 */
    default void close() {
    }
}
