package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * AStudio (acode) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.acode/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("acode")
public class AcodeSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".acode";
    }

    @Override
    public String name() {
        return "acode";
    }
}