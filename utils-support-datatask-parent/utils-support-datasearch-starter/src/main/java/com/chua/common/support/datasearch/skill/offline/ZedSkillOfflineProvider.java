package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Zed Agent 离线技能提供者。
 *
 * <p>扫描 {@code ~/.local/share/zed/agent/skills} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("zed")
public class ZedSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".local/share/zed/agent";
    }

    @Override
    public String name() {
        return "zed";
    }
}