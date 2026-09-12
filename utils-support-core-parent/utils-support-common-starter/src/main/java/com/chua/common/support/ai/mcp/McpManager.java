package com.chua.common.support.ai.mcp;

import java.util.List;
import java.util.Map;

/**
* MCP 管理器接口
*
* <p>集中管理多个 MCP 服务端的注册、发现和工具调用路由。
* 支持从配置或约定目录自动发现 MCP 服务配置。
*
* @author CH
* @since 2026/07/15
 */
public interface McpManager {

    /**
    * 注册 MCP 服务端
    *
    * @param name   服务端名称
    * @param client MCP 客户端
    * @return 当前管理器，支持链式调用
     */
    McpManager register(String name, McpClient client);

    /**
    * 获取指定名称的 MCP 客户端
    *
    * @param name 服务端名称
    * @return MCP 客户端，未找到返回 null
     */
    McpClient get(String name);

    /**
    * 获取所有已注册的 MCP 客户端
    *
    * @return 名称到客户端的映射
     */
    Map<String, McpClient> getAll();

    /**
    * 获取所有 MCP 服务端暴露的工具列表
    *
    * @return 工具描述符列表
     */
    List<McpToolDescriptor> listAllTools();

    /**
    * 调用指定服务端的工具
    *
    * @param serverName 服务端名称
    * @param toolCall   工具调用请求
    * @return 工具调用结果
     */
    McpToolResult callTool(String serverName, McpToolCall toolCall);

    /**
    * 初始化所有已注册的 MCP 客户端
     */
    void initAll();

    /**
    * 关闭所有 MCP 客户端
     */
    void closeAll();
}
