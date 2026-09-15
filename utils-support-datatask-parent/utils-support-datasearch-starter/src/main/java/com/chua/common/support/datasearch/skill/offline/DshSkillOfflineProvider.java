package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Dsh (DeepSeek Harness) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.dsh/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("dsh")
public class DshSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".dsh";
    }

    @Override
    public String name() {
        return "dsh";
    }
}