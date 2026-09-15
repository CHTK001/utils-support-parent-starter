package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Slack MCP 提供者。
 *
 * <p>通过 {@code npx -y @anthropic-ai/slack-mcp} 安装，为 AI 客户端提供 Slack 消息读写能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("slack")
public class SlackMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "slack";
    }

    @Override
    protected String npmPackage() {
        return "@anthropic-ai/slack-mcp";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("slack_post_message", "发送 Slack 消息",
                        Map.of("type", "object", "properties", Map.of(
                                "channel", Map.of("type", "string", "description", "频道 ID"),
                                "text", Map.of("type", "string", "description", "消息文本")),
                                "required", List.of("channel", "text"))),
                new McpToolDescriptor("slack_list_channels", "列出频道",
                        Map.of("type", "object", "properties", Map.of(),
                                "required", List.of())));
    }
}