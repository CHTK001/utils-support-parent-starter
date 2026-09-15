package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Time 时间 MCP 提供者。
 *
 * <p>通过 {@code npx -y @modelcontextprotocol/server-time} 安装，为 AI 客户端提供当前时间查询能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("time")
public class TimeMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "time";
    }

    @Override
    protected String npmPackage() {
        return "@modelcontextprotocol/server-time";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("get_current_time", "获取当前日期时间",
                        Map.of("type", "object", "properties", Map.of(
                                "timezone", Map.of("type", "string", "description", "时区（如 Asia/Shanghai）")),
                                "required", List.of())),
                new McpToolDescriptor("get_current_date", "获取当前日期",
                        Map.of("type", "object", "properties", Map.of(),
                                "required", List.of())));
    }
}