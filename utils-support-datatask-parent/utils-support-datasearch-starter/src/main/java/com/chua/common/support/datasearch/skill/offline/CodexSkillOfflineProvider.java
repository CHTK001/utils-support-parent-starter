package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
* Codex 离线技能提供者。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("codex")
public class CodexSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".codex";
    }

    @Override
    public String name() {
        return "codex";
    }
}
