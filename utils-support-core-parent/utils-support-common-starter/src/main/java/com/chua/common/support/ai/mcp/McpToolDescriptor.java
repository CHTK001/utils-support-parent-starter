package com.chua.common.support.ai.mcp;

import java.util.Map;

/**
* MCP 工具描述符
*
* <p>描述一个 MCP（Model Context Protocol）工具的元信息，包括名称、描述和参数 schema。
*
* @author CH
* @since 2026/07/15
 */
public class McpToolDescriptor {

    /** 工具名称 */
    /**
    * 名称
    */
    private final String name;

    /** 所属 MCP 服务端名称 */
    private final String serverName;

    /** 工具描述 */
    /**
    * 描述
    */
    private final String description;

    /** 参数 schema（JSON Schema 格式） */
    private final Map<String, Object> inputSchema;

    /**
    * 创建 McpToolDescriptor 实例
    * @param name name
    * @param name String
    * @param inputSchema Map
    * @param Object Object
    * @param inputSchema inputSchema
    */
    public McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
        this(name, null, description, inputSchema);
    }

    /**
    * 创建 McpToolDescriptor 实例
    * @param name name
    * @param name String
    * @param name String
    * @param inputSchema Map
    * @param Object Object
    * @param inputSchema inputSchema
    */
    public McpToolDescriptor(String name, String serverName, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.serverName = serverName;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /** 获取Name */
    public String getName() {
        return name;
    }

    /** 获取ServerName */
    public String getServerName() {
        return serverName;
    }

    /** 获取Description */
    public String getDescription() {
        return description;
    }

    /** 获取InputSchema */
    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }
}
