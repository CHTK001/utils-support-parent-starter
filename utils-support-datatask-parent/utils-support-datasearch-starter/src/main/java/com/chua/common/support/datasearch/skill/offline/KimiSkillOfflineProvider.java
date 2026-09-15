package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Kimi (Moonshot 官方 CLI) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.kimi/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("kimi")
public class KimiSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".kimi";
    }

    @Override
    public String name() {
        return "kimi";
    }
}