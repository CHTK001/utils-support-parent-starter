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
* @since 4.0.0.42
 */
@Spi("package-manager")
public class PackageManagerMcpProvider extends PackageManagerProvider implements McpProvider {

    @Override
    /** 名称 */
    public String name() {
        return NAME;
    }

    @Override
    /** 创建 */
    public McpClient create() {
        return new PackageManagerMcpClient();
    }

    @Override
    /** Install */
    public boolean install(String clientId, String skillId) {
        return super.install(clientId, skillId);
    }

    @Override
    /** Uninstall */
    public boolean uninstall(String clientId, String skillId) {
        return super.uninstall(clientId, skillId);
    }

    @Override
    /** 列表installed */
    public Map<String, Boolean> listInstalled() {
        return super.listInstalled();
    }

    @Override
    /** 列表可用 */
    public List<String> listAvailable() {
        return super.listAvailable();
    }
}