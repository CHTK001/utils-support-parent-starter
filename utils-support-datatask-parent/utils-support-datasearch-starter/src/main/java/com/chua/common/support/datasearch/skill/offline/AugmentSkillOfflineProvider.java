package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Augment 编码 离线技能提供者。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("augment")
public class AugmentSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".augment";
    }

    @Override
    public String name() {
        return "augment";
    }
}
