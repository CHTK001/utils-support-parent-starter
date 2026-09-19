package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * 继续 离线技能提供者。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("continue")
public class ContinueSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".continue";
    }

    @Override
    public String name() {
        return "continue";
    }
}
