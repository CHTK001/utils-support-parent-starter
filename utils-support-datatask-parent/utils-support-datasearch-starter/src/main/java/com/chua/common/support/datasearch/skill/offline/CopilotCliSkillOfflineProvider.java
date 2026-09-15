package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Copilot CLI 离线技能提供者。
 *
 * <p>扫描 {@code ~/.copilot/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("copilot-cli")
public class CopilotCliSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".copilot";
    }

    @Override
    public String name() {
        return "copilot-cli";
    }
}