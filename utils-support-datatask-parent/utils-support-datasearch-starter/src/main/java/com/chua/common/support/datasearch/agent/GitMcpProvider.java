package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * Git MCP 提供者。
 *
 * <p>通过 {@code npx -y mcp-server-git} 安装，为 AI 客户端提供 Git 仓库
 * 状态查看、提交历史、文件内容读取等能力。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("git")
public class GitMcpProvider extends AbstractExternalMcpInstallerProvider {

    @Override
    public String name() {
        return "git";
    }

    @Override
    protected String npmPackage() {
        return "mcp-server-git";
    }

    @Override
    protected List<McpToolDescriptor> browserToolDescriptors() {
        return List.of(
                new McpToolDescriptor("git_status", "查看仓库状态",
                        Map.of("type", "object", "properties", Map.of(
                                "repoPath", Map.of("type", "string", "description", "仓库路径")),
                                "required", List.of("repoPath"))),
                new McpToolDescriptor("git_log", "查看提交历史",
                        Map.of("type", "object", "properties", Map.of(
                                "repoPath", Map.of("type", "string", "description", "仓库路径")),
                                "required", List.of("repoPath"))),
                new McpToolDescriptor("git_read_file", "读取文件内容",
                        Map.of("type", "object", "properties", Map.of(
                                "repoPath", Map.of("type", "string", "description", "仓库路径"),
                                "filePath", Map.of("type", "string", "description", "文件路径")),
                                "required", List.of("repoPath", "filePath"))));
    }
}