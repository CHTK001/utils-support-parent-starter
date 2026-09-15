package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Unsloth 离线技能提供者。
 *
 * <p>扫描 {@code ~/.unsloth/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("unsloth")
public class UnslothSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".unsloth";
    }

    @Override
    public String name() {
        return "unsloth";
    }
}