package com.chua.common.support.datasearch.skill.impl;

import com.chua.common.support.ai.mcp.McpClient;
import com.chua.common.support.ai.mcp.McpProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SkillsMP 技能市场 MCP 提供器。
 *
 * <p>通过 SkillsMP 公开 API 搜索技能市场，以 MCP 工具形式暴露给 AI 客户端。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("skillsmp")
public class SkillsmpMcpProvider extends SkillsmpProvider implements McpProvider {

    @Override
    /** Name */
    public String name() {
        return NAME;
    }

    @Override
    /** 创建 */
    public McpClient create() {
        return new SkillsmpMcpClient();
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

    /** ListInstalled */
    public Map<String, Boolean> listInstalled() {
        Map<String, Boolean> result = new HashMap<>();
        result.put(PREFIX + "search", true);
        return result;
    }

    @Override
    /** ListAvailable */
    public List<String> listAvailable() {
        return List.of(NAME);
    }
}