package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * MiMo Code (OpenCode-fork) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.mimocode/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("mimo")
public class MimoSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".mimocode";
    }

    @Override
    public String name() {
        return "mimo";
    }
}