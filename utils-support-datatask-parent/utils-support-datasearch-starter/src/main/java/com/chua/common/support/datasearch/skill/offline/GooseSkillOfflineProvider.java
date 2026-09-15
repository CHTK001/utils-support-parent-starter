package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Goose (Block) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.goose/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("goose")
public class GooseSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".goose";
    }

    @Override
    public String name() {
        return "goose";
    }
}