package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OpenClaw 离线技能提供者。
 *
 * <p>技能位于 OpenClaw 活动工作区下的 {@code skills} 目录：
 * {@code ~/.openclaw/workspace/skills}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("openclaw")
public class OpenClawSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".openclaw";
    }

    @Override
    protected List<Path> skillRoots() {
        String workspace = System.getenv("TOKENTRACKER_OPENCLAW_WORKSPACE");
        Path base;
        if (workspace != null && !workspace.isBlank()) {
            base = Path.of(workspace).toAbsolutePath();
        } else {
            base = USER_HOME.resolve(configDir()).resolve("workspace");
            if (!Files.isDirectory(base)) {
                base = USER_HOME.resolve(configDir());
            }
        }
        return List.of(base);
    }

    @Override
    public String name() {
        return "openclaw";
    }
}