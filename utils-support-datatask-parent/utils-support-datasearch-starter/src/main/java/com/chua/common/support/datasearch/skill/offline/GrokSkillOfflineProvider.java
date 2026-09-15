package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Grok (xAI Build) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.grok/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("grok")
public class GrokSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".grok";
    }

    @Override
    public String name() {
        return "grok";
    }
}