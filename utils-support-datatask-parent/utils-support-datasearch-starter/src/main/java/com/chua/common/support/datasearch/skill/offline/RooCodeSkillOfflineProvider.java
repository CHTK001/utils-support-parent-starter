package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
   * Roo 编码 离线技能提供者。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("roo-code")
public class RooCodeSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".roo";
    }

    @Override
    public String name() {
        return "roo-code";
    }
}
