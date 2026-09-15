package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Sequential Thinking 思维链 MCP 提供者。
 *
 * <p>通过 {@code npx -y @modelcontextprotocol/server-sequential-thinking} 安装，
 * 为 AI 客户端提供结构化、循序渐进的思维链推理能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("sequential-thinking")
public class SequentialThinkingMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "sequential-thinking";
    }

    @Override
    protected String npmPackage() {
        return "@modelcontextprotocol/server-sequential-thinking";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("sequentialthinking", "逐步思考并记录推理过程",
                        Map.of("type", "object", "properties", Map.of(
                                "thought", Map.of("type", "string", "description", "当前思考内容"),
                                "thoughtNumber", Map.of("type", "integer", "description", "思考序号"),
                                "totalThoughts", Map.of("type", "integer", "description", "总思考数"),
                                "nextThoughtNeeded", Map.of("type", "boolean", "description", "是否继续")),
                                "required", List.of("thought", "thoughtNumber", "totalThoughts", "nextThoughtNeeded"))));
    }
}