package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Puppeteer MCP 浏览器自动化提供者。
 *
 * <p>通过 {@code npx -y @puppeteer/mcp} 安装 Puppeteer MCP 服务到 AI 编辑器，
 * 提供浏览器导航、元素操作、截图等浏览器工具。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("puppeteer")
public class PuppeteerMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "puppeteer";
    }

    @Override
    protected String npmPackage() {
        return "@puppeteer/mcp";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("puppeteer_navigate", "导航浏览器到指定 URL",
                        Map.of("type", "object", "properties", Map.of(
                                "url", Map.of("type", "string", "description", "目标网址")),
                                "required", List.of("url"))),
                new McpToolDescriptor("puppeteer_click", "点击页面元素",
                        Map.of("type", "object", "properties", Map.of(
                                "selector", Map.of("type", "string", "description", "CSS 选择器")),
                                "required", List.of("selector"))),
                new McpToolDescriptor("puppeteer_type", "向输入框输入文本",
                        Map.of("type", "object", "properties", Map.of(
                                "selector", Map.of("type", "string", "description", "CSS 选择器"),
                                "text", Map.of("type", "string", "description", "输入文本")),
                                "required", List.of("selector", "text"))),
                new McpToolDescriptor("puppeteer_screenshot", "截取页面截图",
                        Map.of("type", "object", "properties", Map.of(
                                "fullPage", Map.of("type", "boolean", "description", "是否整页截图")),
                                "required", List.of())));
    }
}