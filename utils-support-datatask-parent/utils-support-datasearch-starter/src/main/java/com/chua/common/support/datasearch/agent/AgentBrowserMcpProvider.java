package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Agent Browser 浏览器自动化 MCP 提供者。
 *
 * <p>通过 {@code npx -y agent-browser} 安装浏览器自动化 MCP 服务到 AI 编辑器，
 * 提供网页导航、点击、表单填写、截图等浏览器操作工具。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("agent-browser")
public class AgentBrowserMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "agent-browser";
    }

    @Override
    protected String npmPackage() {
        return "agent-browser";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("navigate", "导航到指定网页",
                        Map.of("type", "object", "properties", Map.of(
                                "url", Map.of("type", "string", "description", "目标网址")),
                                "required", List.of("url"))),
                new McpToolDescriptor("click", "点击页面元素",
                        Map.of("type", "object", "properties", Map.of(
                                "selector", Map.of("type", "string", "description", "CSS 选择器")),
                                "required", List.of("selector"))),
                new McpToolDescriptor("type", "向元素输入文本",
                        Map.of("type", "object", "properties", Map.of(
                                "selector", Map.of("type", "string", "description", "CSS 选择器"),
                                "text", Map.of("type", "string", "description", "输入文本")),
                                "required", List.of("selector", "text"))),
                new McpToolDescriptor("screenshot", "截取当前页面",
                        Map.of("type", "object", "properties", Map.of(),
                                "required", List.of())),
                new McpToolDescriptor("extract", "提取页面内容",
                        Map.of("type", "object", "properties", Map.of(),
                                "required", List.of())));
    }
}