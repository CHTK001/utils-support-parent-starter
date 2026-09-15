package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Chrome DevTools MCP 提供者。
 *
 * <p>通过 {@code npx -y @anthropic-ai/chrome-devtools-mcp} 安装，为 AI 客户端提供 Chrome 浏览器 CDP 自动化。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("chrome-devtools")
public class ChromeDevToolsMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "chrome-devtools";
    }

    @Override
    protected String npmPackage() {
        return "@anthropic-ai/chrome-devtools-mcp";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("cdp_navigate", "导航到 URL",
                        Map.of("type", "object", "properties", Map.of(
                                "url", Map.of("type", "string", "description", "目标网址")),
                                "required", List.of("url"))),
                new McpToolDescriptor("cdp_screenshot", "截取页面截图",
                        Map.of("type", "object", "properties", Map.of(),
                                "required", List.of())));
    }
}