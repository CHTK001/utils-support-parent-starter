package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

/**
 * Qwen (通义千问 CLI) 离线技能提供者。
 *
 * <p>扫描 {@code ~/.qwen/skills、rules、commands} 目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("qwen")
public class QwenSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".qwen";
    }

    @Override
    public String name() {
        return "qwen";
    }
}