package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Memory 知识图谱记忆 MCP 提供者。
 *
 * <p>通过 {@code npx -y @modelcontextprotocol/server-memory} 安装，为 AI 客户端
 * 提供基于知识图谱的持久化记忆能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("memory")
public class MemoryMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    protected String npmPackage() {
        return "@modelcontextprotocol/server-memory";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("memory_create_entities", "创建实体",
                        Map.of("type", "object", "properties", Map.of(
                                "entities", Map.of("type", "array", "description", "实体数组")),
                                "required", List.of("entities"))),
                new McpToolDescriptor("memory_search_nodes", "搜索记忆节点",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词")),
                                "required", List.of("query"))),
                new McpToolDescriptor("memory_reset", "清空记忆库",
                        Map.of("type", "object", "properties", Map.of(),
                                "required", List.of())));
    }
}