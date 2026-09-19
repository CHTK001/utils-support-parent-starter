package com.chua.common.support.datasearch.skill.impl;

import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.spi.annotations.Spi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub 技能仓库 MCP 提供器。
 *
 * <p>通过 GitHub Git Trees API 从默认技能仓库发现 AI 技能，
 * 以 MCP 工具形式暴露给 AI 客户端。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("github")
public class GithubMcpProvider extends GithubSkillProvider implements McpProvider {

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public McpClient create() {
        return new GithubMcpClient();
    }

    @Override
    public boolean install(String clientId, String skillId) {
        return super.install(clientId, skillId);
    }

    @Override
    public boolean uninstall(String clientId, String skillId) {
        return super.uninstall(clientId, skillId);
    }

    /**
     * 列出已安装项。
     *
     * @return 安装状态
     */
    public Map<String, Boolean> listInstalled() {
        Map<String, Boolean> result = new HashMap<>();
        result.put(PREFIX + "discover", true);
        return result;
    }

    @Override
    public List<String> listAvailable() {
        return List.of(NAME);
    }

    /**
     * GitHub 技能 MCP 客户端实现。
     *
     * @author CH
     * @since 4.0.0.45
     */
    protected class GithubMcpClient implements McpClient {
        /** initialized */
        private volatile boolean initialized = false;

        @Override
        public void init() {
            initialized = true;
            log.info("GitHub 技能 MCP 客户端已初始化");
        }

        @Override
        public List<McpToolDescriptor> listTools() {
            return GithubSkillProvider.toolDescriptors();
        }

        @Override
        public McpToolResult callTool(McpToolCall toolCall) {
            try {
                if ((PREFIX + "discover").equals(toolCall.getToolName())) {
                    return handleDiscover(toolCall.getArguments());
                }
                return McpToolResult.error("未知工具: " + toolCall.getToolName());
            } catch (Exception e) {
                log.error("GitHub 技能工具调用异常", e);
                return McpToolResult.error("调用异常: " + e.getMessage());
            }
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }
    }
}
