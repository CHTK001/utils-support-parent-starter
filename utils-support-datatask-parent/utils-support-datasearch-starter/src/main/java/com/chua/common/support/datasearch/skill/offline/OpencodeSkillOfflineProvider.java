package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * OpenCode 离线技能提供者。
 *
 * <p>扫描 {@code ~/.config/opencode/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("opencode")
public class OpencodeSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".config/opencode";
    }

    @Override
    public String name() {
        return "opencode";
    }
}