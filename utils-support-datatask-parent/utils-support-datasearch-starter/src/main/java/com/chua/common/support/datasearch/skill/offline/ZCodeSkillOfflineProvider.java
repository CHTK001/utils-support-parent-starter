package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * ZCode (Z.ai 编码智能体) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.zcode/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("zcode")
public class ZCodeSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".zcode";
    }

    @Override
    public String name() {
        return "zcode";
    }
}