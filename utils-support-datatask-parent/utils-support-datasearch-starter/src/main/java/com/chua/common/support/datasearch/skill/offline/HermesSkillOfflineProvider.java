package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Hermes 离线技能提供者。
 *
 * <p>扫描 {@code ~/.hermes/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("hermes")
public class HermesSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".hermes";
    }

    @Override
    public String name() {
        return "hermes";
    }
}