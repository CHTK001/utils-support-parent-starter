package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
* Trae 离线技能提供者。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("trae")
public class TraeSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".trae";
    }

    @Override
    public String name() {
        return "trae";
    }
}
