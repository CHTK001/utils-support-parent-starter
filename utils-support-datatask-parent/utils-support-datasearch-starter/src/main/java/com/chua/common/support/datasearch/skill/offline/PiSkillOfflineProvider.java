package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Pi (路由Agent) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.pi/agent/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("pi")
public class PiSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".pi/agent";
    }

    @Override
    public String name() {
        return "pi";
    }
}
