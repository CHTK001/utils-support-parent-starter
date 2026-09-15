package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Agents（通用 .agents 技能目录）离线技能提供者。
 *
 * <p>扫描 {@code ~/.agents/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("agents")
public class AgentsSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".agents";
    }

    @Override
    public String name() {
        return "agents";
    }
}