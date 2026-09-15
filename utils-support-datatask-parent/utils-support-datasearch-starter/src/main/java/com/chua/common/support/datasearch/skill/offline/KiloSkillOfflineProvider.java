package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Kilo (kilo.ai CLI) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.config/kilo/skills}（OpenCode-fork 约定）目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("kilo")
public class KiloSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".config/kilo";
    }

    @Override
    public String name() {
        return "kilo";
    }
}