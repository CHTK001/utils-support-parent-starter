package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * CommandCode 离线技能提供者。
 *
 * <p>扫描 {@code ~/.commandcode/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("command-code")
public class CommandCodeSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".commandcode";
    }

    @Override
    public String name() {
        return "command-code";
    }
}