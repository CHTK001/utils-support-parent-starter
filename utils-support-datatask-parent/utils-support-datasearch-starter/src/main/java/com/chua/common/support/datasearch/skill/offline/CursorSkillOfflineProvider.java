package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Cursor 离线技能提供者。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("cursor")
public class CursorSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".cursor";
    }

    @Override
    public String name() {
        return "cursor";
    }
}
