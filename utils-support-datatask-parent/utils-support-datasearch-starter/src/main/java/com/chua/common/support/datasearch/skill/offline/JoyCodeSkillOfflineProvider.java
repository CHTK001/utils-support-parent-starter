package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * JoyCode 离线技能提供者。
 *
 * <p>扫描 {@code ~/.joycode/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("joycode")
public class JoyCodeSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".joycode";
    }

    @Override
    public String name() {
        return "joycode";
    }
}