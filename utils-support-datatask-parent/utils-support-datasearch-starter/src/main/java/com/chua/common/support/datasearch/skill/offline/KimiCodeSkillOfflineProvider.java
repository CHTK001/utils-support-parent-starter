package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * KimiCode 离线技能提供者。
 *
 * <p>扫描 {@code ~/.kimi-code/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("kimi-code")
public class KimiCodeSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".kimi-code";
    }

    @Override
    public String name() {
        return "kimi-code";
    }
}