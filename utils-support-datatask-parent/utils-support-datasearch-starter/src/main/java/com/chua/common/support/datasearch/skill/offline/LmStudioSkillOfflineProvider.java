package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * LM Studio 离线技能提供者。
 *
 * <p>扫描 {@code ~/.lmstudio/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("lmstudio")
public class LmStudioSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".lmstudio";
    }

    @Override
    public String name() {
        return "lmstudio";
    }
}