package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * AnythingLlm 离线技能提供者。
 *
 * <p>扫描 {@code ~/.config/anythingllm/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("anythingllm")
public class AnythingLlmSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".config/anythingllm";
    }

    @Override
    public String name() {
        return "anythingllm";
    }
}