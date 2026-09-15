package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Droid (Factory CLI) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.factory/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("droid")
public class DroidSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".factory";
    }

    @Override
    public String name() {
        return "droid";
    }
}