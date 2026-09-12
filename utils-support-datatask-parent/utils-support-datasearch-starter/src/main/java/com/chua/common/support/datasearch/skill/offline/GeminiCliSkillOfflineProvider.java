package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
* Gemini CLI 离线技能提供者。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("gemini-cli")
public class GeminiCliSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".gemini";
    }

    @Override
    public String name() {
        return "gemini-cli";
    }
}
