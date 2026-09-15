package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Notion MCP 提供者。
 *
 * <p>通过 {@code npx -y @anthropic-ai/notion-mcp} 安装，为 AI 客户端提供 Notion 知识库访问能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("notion")
public class NotionMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "notion";
    }

    @Override
    protected String npmPackage() {
        return "@anthropic-ai/notion-mcp";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("notion_search", "搜索 Notion 页面",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词")),
                                "required", List.of("query"))),
                new McpToolDescriptor("notion_read", "读取页面内容",
                        Map.of("type", "object", "properties", Map.of(
                                "pageId", Map.of("type", "string", "description", "页面 ID")),
                                "required", List.of("pageId"))),
                new McpToolDescriptor("notion_write", "写入页面内容",
                        Map.of("type", "object", "properties", Map.of(
                                "pageId", Map.of("type", "string", "description", "页面 ID"),
                                "content", Map.of("type", "string", "description", "Markdown 内容")),
                                "required", List.of("pageId", "content"))));
    }
}