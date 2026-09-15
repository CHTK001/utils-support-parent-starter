package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * WorkBuddy 离线技能提供者。
 *
 * <p>扫描 {@code ~/.workbuddy/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("workbuddy")
public class WorkBuddySkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".workbuddy";
    }

    @Override
    public String name() {
        return "workbuddy";
    }
}