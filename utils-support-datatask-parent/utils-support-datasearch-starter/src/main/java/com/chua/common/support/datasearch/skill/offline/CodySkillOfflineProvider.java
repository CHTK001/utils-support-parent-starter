package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
* Cody（Sourcegraph）离线技能提供者。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("cody")
public class CodySkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".cody";
    }

    @Override
    public String name() {
        return "cody";
    }
}
