package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * AtomCode 离线技能提供者。
 *
 * <p>扫描 {@code ~/.atomcode/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("atomcode")
public class AtomCodeSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".atomcode";
    }

    @Override
    public String name() {
        return "atomcode";
    }
}