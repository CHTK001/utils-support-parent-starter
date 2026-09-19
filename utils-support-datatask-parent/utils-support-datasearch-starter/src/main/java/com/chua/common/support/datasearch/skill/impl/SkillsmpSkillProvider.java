package com.chua.common.support.datasearch.skill.impl;

import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.skill.spi.SkillOnlineProvider;

import java.util.List;

/**
 * skillsmp 技能市场在线提供器。
 *
 * <p>通过 SkillsMP 公开 API 搜索技能市场，以 Skill 形式暴露给 AI 技能系统。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("skillsmp")
public class SkillsmpSkillProvider extends SkillsmpProvider implements SkillOnlineProvider {

    @Override
    /**
     * 名称
    */
    public String name() {
        return NAME;
    }

    @Override
    /**
     * 获取Skills
    */
    public List<SkillDefinition> getSkills() {
        return List.of(searchSkill());
    }

    @Override
    /**
     * 搜索
    */
    public List<SkillDefinition> search(String keyword) {
        return List.of(searchSkill());
    }

    @Override
    /**
     * Install
    */
    public boolean install(String clientId, String skillId) {
        return super.install(clientId, skillId);
    }

    @Override
    /**
     * Uninstall
    */
    public boolean uninstall(String clientId, String skillId) {
        return super.uninstall(clientId, skillId);
    }
}