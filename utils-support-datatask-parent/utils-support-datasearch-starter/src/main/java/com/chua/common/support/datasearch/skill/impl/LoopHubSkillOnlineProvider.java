package com.chua.common.support.datasearch.skill.impl;

import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.datasearch.skill.spi.SkillOnlineProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * LoopHub 循环hub 在线技能市场提供者。
 *
 * <p>通过 {@code https://hub.cocoloop.cn/popular} 展示 AI 技能，
 * 以 Skill 形式暴露给 AI 技能系统。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("loophub")
public class LoopHubSkillOnlineProvider extends SkillShProvider implements SkillOnlineProvider {

    @Override
    public String name() {
        return "loophub";
    }

    @Override
    public List<SkillDefinition> getSkills() {
        return List.of(searchSkill());
    }

    @Override
    public List<SkillDefinition> search(String keyword) {
        return List.of(searchSkill());
    }

    @Override
    public boolean install(String clientId, String skillId) {
        return super.install(clientId, skillId);
    }

    @Override
    public boolean uninstall(String clientId, String skillId) {
        return super.uninstall(clientId, skillId);
    }
}