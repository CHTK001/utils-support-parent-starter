package com.chua.common.support.datasearch.skill.impl;

import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.datasearch.skill.spi.SkillOnlineProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * GitHub 技能仓库在线技能提供器。
 *
 * <p>通过 GitHub Git Trees API 从默认技能仓库发现 AI 技能，
 * 以 Skill 形式暴露给 AI 技能系统。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("github")
public class GithubSkillOnlineProvider extends GithubSkillProvider implements SkillOnlineProvider {

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SkillDefinition> getSkills() {
        return List.of(discoverSkill());
    }

    @Override
    public List<SkillDefinition> search(String keyword) {
        return List.of(discoverSkill());
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