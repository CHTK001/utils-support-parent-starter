package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Qoder (通义灵码 智能体) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.qoder/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("qoder")
public class QoderSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".qoder";
    }

    @Override
    public String name() {
        return "qoder";
    }
}