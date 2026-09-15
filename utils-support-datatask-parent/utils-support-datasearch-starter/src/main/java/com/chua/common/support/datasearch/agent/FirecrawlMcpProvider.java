package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Firecrawl 网页抓取 MCP 提供者。
 *
 * <p>通过 {@code npx -y firecrawl-mcp} 安装，提供网页抓取、搜索、
 * 爬取与 Markdown 转换能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("firecrawl")
public class FirecrawlMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "firecrawl";
    }

    @Override
    protected String npmPackage() {
        return "firecrawl-mcp";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("firecrawl_scrape", "抓取网页转 Markdown",
                        Map.of("type", "object", "properties", Map.of(
                                "url", Map.of("type", "string", "description", "目标网址")),
                                "required", List.of("url"))),
                new McpToolDescriptor("firecrawl_search", "搜索网页内容",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词")),
                                "required", List.of("query"))),
                new McpToolDescriptor("firecrawl_crawl", "递归爬取站点",
                        Map.of("type", "object", "properties", Map.of(
                                "url", Map.of("type", "string", "description", "起始网址")),
                                "required", List.of("url"))));
    }
}