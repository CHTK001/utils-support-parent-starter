package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * OMP 离线技能提供者。
 *
 * <p>扫描 {@code ~/.omp/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("omp")
public class OmpSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".omp";
    }

    @Override
    public String name() {
        return "omp";
    }
}