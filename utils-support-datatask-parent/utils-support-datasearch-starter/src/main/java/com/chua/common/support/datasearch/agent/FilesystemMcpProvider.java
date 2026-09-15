package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Filesystem 文件系统 MCP 提供者。
 *
 * <p>通过 {@code npx -y @modelcontextprotocol/server-filesystem} 安装，为 AI 客户端提供文件读写能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("filesystem")
public class FilesystemMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "filesystem";
    }

    @Override
    protected String npmPackage() {
        return "@modelcontextprotocol/server-filesystem";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("read_file", "读取文件内容",
                        Map.of("type", "object", "properties", Map.of(
                                "path", Map.of("type", "string", "description", "文件路径")),
                                "required", List.of("path"))),
                new McpToolDescriptor("write_file", "写入文件内容",
                        Map.of("type", "object", "properties", Map.of(
                                "path", Map.of("type", "string", "description", "文件路径"),
                                "content", Map.of("type", "string", "description", "文件内容")),
                                "required", List.of("path", "content"))),
                new McpToolDescriptor("list_directory", "列出目录内容",
                        Map.of("type", "object", "properties", Map.of(
                                "path", Map.of("type", "string", "description", "目录路径")),
                                "required", List.of("path"))));
    }
}