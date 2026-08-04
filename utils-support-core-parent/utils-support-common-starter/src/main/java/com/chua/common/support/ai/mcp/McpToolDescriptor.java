package com.chua.common.support.ai.mcp;

import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * MCP 工具描述符
 *
 * <p>描述一个 MCP（Model Context Protocol）工具的元信息，包括名称、描述和参数 schema。
 *
 * @author CH
 * @since 2026/07/15
 */
@SuppressWarnings("NullAway")
@NullUnmarked
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

    public McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
        this(name, null, description, inputSchema);
    }

    public McpToolDescriptor(String name, String serverName, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.serverName = serverName;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public String getServerName() {
        return serverName;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }
}