package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * PrimeAgent (PrimeIntellect) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.prime/agent/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("prime-agent")
public class PrimeAgentSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".prime/agent";
    }

    @Override
    public String name() {
        return "prime-agent";
    }
}