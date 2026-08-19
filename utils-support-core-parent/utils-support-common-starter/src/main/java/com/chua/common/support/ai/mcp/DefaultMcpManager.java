package com.chua.common.support.ai.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 MCP 管理器（进程内注册表），统一管理多个 MCP 服务端的注册、发现和工具调用路由。
 *
 * @author CH
 * @since 2026/07/27
 */
public class DefaultMcpManager implements McpManager {

    /** MCP 客户端注册表，键为服务端名称 */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    @Override
    /** 注册 */
    public McpManager register(String name, McpClient client) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP 客户端名称不能为空");
        }
        if (client == null) {
            throw new IllegalArgumentException("MCP 客户端不能为空");
        }
        clients.put(name, client);
        return this;
    }

    @Override
    /** 获取 */
    public McpClient get(String name) {
        return name == null ? null : clients.get(name);
    }

    @Override
    /** 获取All */
    public Map<String, McpClient> getAll() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(clients));
    }

    @Override
    /** ListAllTools */
    public List<McpToolDescriptor> listAllTools() {
        List<McpToolDescriptor> allTools = new ArrayList<>();
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            McpClient client = entry.getValue();
            if (client == null || !client.isInitialized()) {
                continue;
            }
            List<McpToolDescriptor> tools = client.listTools();
            if (tools != null) {
                allTools.addAll(tools);
            }
        }
        return allTools;
    }

    @Override
    /** 调用Tool */
    public McpToolResult callTool(String serverName, McpToolCall toolCall) {
        McpClient client = get(serverName);
        if (client == null) {
            return McpToolResult.error("未注册 MCP 服务端: " + serverName);
        }
        if (!client.isInitialized()) {
            return McpToolResult.error("MCP 服务端未初始化: " + serverName);
        }
        try {
            return client.callTool(toolCall);
        } catch (Exception e) {
            return McpToolResult.error("MCP 工具调用异常: " + e.getMessage());
        }
    }

    @Override
    /** 初始化All */
    public void initAll() {
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().init();
            } catch (Exception e) {
                // 忽略单个客户端初始化失败，继续初始化其他客户端
            }
        }
    }

    @Override
    /** 关闭All */
    public void closeAll() {
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                // 忽略单个客户端关闭失败
            }
        }
        clients.clear();
    }
}