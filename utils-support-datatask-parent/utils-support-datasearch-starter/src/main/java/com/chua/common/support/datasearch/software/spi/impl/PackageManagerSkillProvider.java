package com.chua.common.support.datasearch.software.spi.impl;

import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.skill.spi.SkillProvider;

import java.util.List;
import java.util.Map;

/**
 * 包管理器 Skill 提供器。
 *
 * <p>通过系统包管理器（winget/brew/apt 等）搜索和安装软件，以 Skill 形式暴露给 AI 技能系统。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("package-manager")
public class PackageManagerSkillProvider extends PackageManagerProvider implements SkillProvider {

    @Override
    /** Name */
    public String name() {
        return NAME;
    }

    @Override
    /** 获取Skills */
    public List<SkillDefinition> getSkills() {
        return List.of(
                softwareSearchSkill(),
                softwareInstallSkill(),
                softwareUninstallSkill(),
                listPackageManagersSkill()
        );
    }

    @Override
    /** Install */
    public boolean install(String clientId, String skillId) {
        return super.install(clientId, skillId);
    }

    @Override
    /** Uninstall */
    public boolean uninstall(String clientId, String skillId) {
        return super.uninstall(clientId, skillId);
    }

    @Override
    /** ListInstalled */
    public Map<String, Boolean> listInstalled() {
        return super.listInstalled();
    }

    @Override
    /** ListAvailable */
    public List<String> listAvailable() {
        return super.listAvailable();
    }
}