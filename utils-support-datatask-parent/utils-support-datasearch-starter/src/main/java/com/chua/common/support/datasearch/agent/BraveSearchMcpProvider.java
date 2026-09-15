package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Brave Search 搜索 MCP 提供者。
 *
 * <p>通过 {@code npx -y @modelcontextprotocol/server-brave-search} 安装，
 * 为 AI 客户端提供网页搜索能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("brave-search")
public class BraveSearchMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "brave-search";
    }

    @Override
    protected String npmPackage() {
        return "@modelcontextprotocol/server-brave-search";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("brave_web_search", "执行网页搜索",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词"),
                                "count", Map.of("type", "integer", "description", "结果数量")),
                                "required", List.of("query"))));
    }
}