package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Fetch HTTP 抓取 MCP 提供者。
 *
 * <p>通过 {@code npx -y @modelcontextprotocol/server-fetch} 安装，为 AI 客户端
 * 提供 HTTP 资源抓取与内容提取能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("fetch")
public class FetchMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "fetch";
    }

    @Override
    protected String npmPackage() {
        return "@modelcontextprotocol/server-fetch";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("fetch", "抓取指定 URL 的文本内容",
                        Map.of("type", "object", "properties", Map.of(
                                "url", Map.of("type", "string", "description", "目标网址"),
                                "maxLength", Map.of("type", "integer", "description", "最大长度")),
                                "required", List.of("url"))),
                new McpToolDescriptor("search", "抓取搜索页并返回链接",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词")),
                                "required", List.of("query"))));
    }
}