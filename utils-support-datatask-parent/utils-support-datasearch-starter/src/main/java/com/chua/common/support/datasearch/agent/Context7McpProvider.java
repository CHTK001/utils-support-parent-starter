package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Context7 文档检索 MCP 提供者。
 *
 * <p>通过 {@code npx -y @upstash/context7-mcp} 安装，为 AI 客户端提供
 * 实时、最新的第三方库文档上下文检索能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("context7")
public class Context7McpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "context7";
    }

    @Override
    protected String npmPackage() {
        return "@upstash/context7-mcp";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("context7_query", "检索库的实时文档上下文",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "检索问题"),
                                "library", Map.of("type", "string", "description", "库名（如 react / axios）"),
                                "topic", Map.of("type", "string", "description", "主题")),
                                "required", List.of("query", "library"))),
                new McpToolDescriptor("context7_docs", "获取库的文档信息",
                        Map.of("type", "object", "properties", Map.of(
                                "library", Map.of("type", "string", "description", "库名")),
                                "required", List.of("library"))));
    }
}