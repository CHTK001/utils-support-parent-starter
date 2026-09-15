package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Antigravity 离线技能提供者。
 *
 * <p>Antigravity 分主应用与 IDE 两个技能目录：
 * {@code ~/.antigravity/skills} 与 {@code ~/.antigravity-ide/skills}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("antigravity")
public class AntigravitySkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    @Override
    protected String configDir() {
        return ".antigravity";
    }

    @Override
    protected List<Path> skillRoots() {
        List<Path> roots = new ArrayList<>();
        for (String dir : new String[]{".antigravity", ".antigravity-ide"}) {
            Path parent = USER_HOME.resolve(dir);
            if (Files.isDirectory(parent)) {
                roots.add(parent);
            }
        }
        if (roots.isEmpty()) {
            roots.add(USER_HOME.resolve(configDir()));
        }
        return roots;
    }

    @Override
    public String name() {
        return "antigravity";
    }
}