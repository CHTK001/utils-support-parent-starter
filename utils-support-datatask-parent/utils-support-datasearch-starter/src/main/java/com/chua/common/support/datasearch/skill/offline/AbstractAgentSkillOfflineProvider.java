package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.datasearch.agent.AgentEditorProvider;
import com.chua.common.support.datasearch.skill.spi.SkillOfflineProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 离线技能提供者抽象基类。
 *
 * <p>每个具体 agent（Cursor、Claude Code、Codex 等）子类声明自己的配置目录
 * 与 SPI 名称，扫描 {@code <configDir>/skills、rules、commands} 下的 SKILL.md 技能。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractAgentSkillOfflineProvider implements SkillOfflineProvider {

    /**
     * 用户主目录
     */
    protected static final Path USER_HOME = Paths.get(System.getProperty("user.home", "."));

    /**
     * 配置目录相对路径（如 .cursor、.claude）。
     *
     * @return 配置目录
     */
    protected abstract String configDir();

    /**
     * 工作区级 agent 返回 true（如 CodeBuddy），主目录级返回 false。
     *
     * @return 是否工作区级
     */
    protected boolean workspaceBased() {
        return false;
    }

    @Override
    public boolean isInstalled() {
        if (workspaceBased()) {
            return findWorkspaceConfig() != null;
        }
        return Files.isDirectory(USER_HOME.resolve(configDir()));
    }

    @Override
    public List<SkillDefinition> listAgentSkills() {
        List<SkillDefinition> result = new ArrayList<>();
        if (workspaceBased()) {
            Path base = findWorkspaceConfig();
            if (base == null) {
                return result;
            }
            scanDirs(base, result);
            return result;
        }
        Path base = USER_HOME.resolve(configDir());
        scanDirs(base, result);
        return result;
    }

    /**
     * 扫描目录下的技能（skills/rules/commands 子目录，SKILL.md 或单文件规则）。
     *
     * @param base   配置根目录
     * @param result 结果收集器
     */
    private void scanDirs(Path base, List<SkillDefinition> result) {
        if (!Files.isDirectory(base)) {
            return;
        }
        for (String sub : new String[]{"skills", "rules", "commands"}) {
            Path dir = base.resolve(sub);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                for (Path p : stream.sorted().toList()) {
                    if (Files.isDirectory(p)) {
                        Path skillMd = p.resolve("SKILL.md");
                        if (!Files.exists(skillMd)) {
                            skillMd = p.resolve("skill.md");
                        }
                        if (Files.exists(skillMd)) {
                            SkillDefinition def = parseSkillMd(skillMd, p.getFileName().toString());
                            if (def != null) {
                                result.add(def);
                            }
                        }
                    } else if (p.toString().endsWith(".md") || p.toString().endsWith(".mdc")) {
                        String name = p.getFileName().toString().replaceAll("\\.(md|mdc)$", "");
                        SkillDefinition def = parseSkillMd(p, name);
                        if (def != null) {
                            result.add(def);
                        }
                    }
                }
            } catch (IOException e) {
                // 忽略单个子目录扫描异常
            }
        }
    }

    /**
     * 工作区级 agent 向上查找配置目录（如 .codebuddy）。
     *
     * @return 找到的配置根目录；未找到返回 null
     */
    private Path findWorkspaceConfig() {
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 64 && current != null; i++) {
            Path config = current.resolve(configDir());
            if (Files.isDirectory(config)) {
                return config;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 解析 SKILL.md 文件为 SkillDefinition。
     *
     * @param skillMdFile SKILL.md 文件路径
     * @param skillName   技能名称（目录名）
     * @return SkillDefinition 实例；解析失败返回 null
     */
    private SkillDefinition parseSkillMd(Path skillMdFile, String skillName) {
        try {
            String content = Files.readString(skillMdFile, StandardCharsets.UTF_8);
            String description = "";
            for (String line : content.lines().toList()) {
                if (line.startsWith("##") || line.startsWith("description:")) {
                    description = line.replaceFirst("^#{1,4}\\s*", "")
                            .replaceFirst("^description:\\s*", "").trim();
                    break;
                }
            }
            return SkillDefinition.skill(skillName, description, skillMdFile.toString());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String name() {
        return "agent";
    }

    /**
     * 解析技能在本机的真实落盘位置。
     *
     * <p>在与 {@link #listAgentSkills()} 相同的目录下（{@code skills/rules/commands}）
     * 查找与技能名匹配的目录（含 SKILL.md/skill.md）或单文件规则（{@code <name>.md/.mdc}），
     * 供后端将 {@code agent://PROVIDER/SKILL} 虚拟地址解析为可导入路径。</p>
     *
     * @param skillName 技能名（目录名或去掉扩展名的 .md 文件名）
     * @return 落盘路径；找不到返回 null
     */
    @Override
    public Path resolveSkillPath(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        Path base = workspaceBased() ? findWorkspaceConfig() : USER_HOME.resolve(configDir());
        if (base == null) {
            return null;
        }
        for (String sub : new String[]{"skills", "rules", "commands"}) {
            Path dir = base.resolve(sub);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Path dirMatch = dir.resolve(skillName);
            if (Files.isDirectory(dirMatch)
                    && (Files.exists(dirMatch.resolve("SKILL.md")) || Files.exists(dirMatch.resolve("skill.md")))) {
                return dirMatch;
            }
            for (String ext : new String[]{".md", ".mdc"}) {
                Path fileMatch = dir.resolve(skillName + ext);
                if (Files.isRegularFile(fileMatch)) {
                    return fileMatch;
                }
            }
        }
        return null;
    }
}
