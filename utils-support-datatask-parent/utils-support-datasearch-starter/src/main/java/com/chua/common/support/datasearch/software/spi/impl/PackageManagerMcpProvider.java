package com.chua.common.support.datasearch.software.spi.impl;

import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;
import java.util.Map;

/**
 * 包管理器 MCP 提供器。
 *
 * <p>通过系统包管理器（winget/brew/apt 等）搜索和安装软件，以 MCP 工具形式暴露给 AI 客户端。
 *
 * @author CH
 * @since 2026/07/27
 */
@Spi("package-manager")
public class PackageManagerMcpProvider extends PackageManagerProvider implements McpProvider {

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public McpClient create() {
        return new PackageManagerMcpClient();
    }

    @Override
    public boolean install(String clientId, String skillId) {
        return super.install(clientId, skillId);
    }

    @Override
    public boolean uninstall(String clientId, String skillId) {
        return super.uninstall(clientId, skillId);
    }

    @Override
    public Map<String, Boolean> listInstalled() {
        return super.listInstalled();
    }

    @Override
    public List<String> listAvailable() {
        return super.listAvailable();
    }
}