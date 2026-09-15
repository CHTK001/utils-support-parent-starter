package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Playwright MCP 浏览器自动化提供者。
 *
 * <p>通过 {@code npx -y @playwright/mcp@latest} 安装 Playwright MCP 服务到 AI 编辑器，
 * 提供浏览器导航、元素交互、截图等浏览器操作工具。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("playwright")
public class PlaywrightMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "playwright";
    }

    @Override
    protected String npmPackage() {
        return "@playwright/mcp@latest";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("browser_navigate", "导航浏览器到指定 URL",
                        Map.of("type", "object", "properties", Map.of(
                                "url", Map.of("type", "string", "description", "目标网址")),
                                "required", List.of("url"))),
                new McpToolDescriptor("browser_click", "点击页面元素",
                        Map.of("type", "object", "properties", Map.of(
                                "selector", Map.of("type", "string", "description", "CSS 选择器")),
                                "required", List.of("selector"))),
                new McpToolDescriptor("browser_type", "向输入框输入文本",
                        Map.of("type", "object", "properties", Map.of(
                                "selector", Map.of("type", "string", "description", "CSS 选择器"),
                                "text", Map.of("type", "string", "description", "输入文本")),
                                "required", List.of("selector", "text"))),
                new McpToolDescriptor("browser_snapshot", "获取页面可访问性快照",
                        Map.of("type", "object", "properties", Map.of(),
                                "required", List.of())),
                new McpToolDescriptor("browser_screenshot", "截取页面截图",
                        Map.of("type", "object", "properties", Map.of(
                                "fullPage", Map.of("type", "boolean", "description", "是否整页截图")),
                                "required", List.of())));
    }
}