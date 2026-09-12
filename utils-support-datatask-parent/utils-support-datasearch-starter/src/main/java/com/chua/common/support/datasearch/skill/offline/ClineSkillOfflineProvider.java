package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
* Cline 离线技能提供者。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("cline")
public class ClineSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".vscode-server/data/User/globalStorage/saoudrizwan.claude-dev/settings";
    }

    @Override
    public String name() {
        return "cline";
    }
}
