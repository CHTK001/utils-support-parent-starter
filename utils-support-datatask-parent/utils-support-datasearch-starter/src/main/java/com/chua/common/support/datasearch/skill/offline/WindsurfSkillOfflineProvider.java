package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Windsurf 离线技能提供者。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("windsurf")
public class WindsurfSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".windsurf";
    }

    @Override
    public String name() {
        return "windsurf";
    }
}
