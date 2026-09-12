package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
* 编码buddy 离线技能提供者（工作区级）。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("codebuddy")
public class CodeBuddySkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".codebuddy";
    }

    @Override
    protected boolean workspaceBased() {
        return true;
    }

    @Override
    public String name() {
        return "codebuddy";
    }
}
