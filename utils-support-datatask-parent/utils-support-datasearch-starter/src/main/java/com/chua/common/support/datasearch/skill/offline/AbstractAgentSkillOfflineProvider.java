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
     * 配置目录相对路径（如 .Cursor、.claude）。
     *
     * @return 配置目录
     */
    protected abstract String configDir();

    /**
     * 工作区级 Agent 返回 true（如 编码buddy），主目录级返回 false。
     *
     * @return 是否工作区级
     */
    protected boolean workspaceBased() {
        return false;
    }

    /**
     * 技能扫描根目录列表。
     *
     * <p>默认返回 {@code USER_HOME/&#60;configDir&#62;)} 目录；
     * workspaceBased 的Agent返回工作区目录。</p>
     *
     * @return 技能根目录列表
     */
    protected List<Path> skillRoots() {
        if (workspaceBased()) {
            Path base = findWorkspaceConfig();
            return base == null ? List.of() : List.of(base);
        }
        return List.of(USER_HOME.resolve(configDir()));
    }

    @Override
    public boolean isInstalled() {
        for (Path root : skillRoots()) {
            if (Files.isDirectory(root)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<SkillDefinition> listAgentSkills() {
        List<SkillDefinition> result = new ArrayList<>();
        for (Path base : skillRoots()) {
            scanDirs(base, result);
        }
        return result;
    }

    /**
     * 扫描目录下的技能（skills/rules/命令 子目录，SKILL.md 或单文件规则）。
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
     * 工作区级 Agent 向上查找配置目录（如 .codebuddy）。
     *
     * @return 找到的配置根目录；未找到返回 空
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
     * 解析 SKILL.md 文件为 skilldefinition。
     *
     * <p>描述提取优先级：</p>
     * <ol>
     *   <li>YAML front-matter（{@code ---} 包围块）内的 {@code description:} 字段</li>
     *   <li>正文中首个 {@code description:} 行（兼容无 front-matter 的写法）</li>
     *   <li>正文首个 Markdown 标题（{@code ##} 起）</li>
     *   <li>空串</li>
     * </ol>
     *
     * @param skillMdFile SKILL.md 文件路径
     * @param skillName   技能名称（目录名）
     * @return SkillDefinition 实例；解析失败返回 空
     */
     private SkillDefinition parseSkillMd(Path skillMdFile, String skillName) {
         try {
             String content = Files.readString(skillMdFile, StandardCharsets.UTF_8);
             String description = extractDescription(content);
             return SkillDefinition.skill(skillName, description, skillMdFile.toString());
         } catch (IOException e) {
             return null;
         }
     }

     /**
      * 从 SKILL.md 内容提取描述文本（三级回退）。
      *
      * @param content 文件内容
      * @return 描述；无内容时返回 空串
      */
     private static String extractDescription(String content) {
         if (content == null || content.isBlank()) {
             return "";
         }
         List<String> lines = content.lines().toList();
         // 1. YAML front-matter 块（首行须为 ---）
         if (!lines.isEmpty() && lines.getFirst().startsWith("---")) {
             String frontMatterDesc = parseFrontMatterDescription(lines);
             if (!frontMatterDesc.isEmpty()) {
                 return frontMatterDesc;
             }
         }
         // 2. 任意位置的首个 description: 行
         for (String line : lines) {
             if (line.startsWith("description:")) {
                 String value = line.replaceFirst("^description:\\s*", "").trim();
                 if (!value.isEmpty()) {
                     return value;
                 }
             }
         }
         // 3. 首个 Markdown 标题（## 及以上）
         for (String line : lines) {
             if (line.startsWith("#")) {
                 String value = line.replaceFirst("^#{1,6}\\s*", "").trim();
                 if (!value.isEmpty()) {
                     return value;
                 }
             }
         }
         return "";
     }

     /**
      * 解析 YAML front-matter 块内的 description 字段。
      *
      * <p>仅解析简单 {@code key: value} 形式，支持双引号/单引号包裹值；
      * 块结束于第二个 {@code ---}（或文件尾）。</p>
      *
      * @param lines 全文行列表
      * @return description 值；未找到返回 空串
      */
     private static String parseFrontMatterDescription(List<String> lines) {
         for (int i = 1; i < lines.size(); i++) {
             String line = lines.get(i);
             if (line.startsWith("---")) {
                 break;
             }
             String trimmed = line.trim();
             if (trimmed.startsWith("description:")) {
                 String value = trimmed.replaceFirst("^description:\\s*", "").trim();
                 if (value.length() >= 2) {
                     char first = value.charAt(0);
                     char last = value.charAt(value.length() - 1);
                     if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                         value = value.substring(1, value.length() - 1);
                     }
                 }
                 return value;
             }
         }
         return "";
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
     * @return 落盘路径；找不到返回 空
     */
    @Override
    public Path resolveSkillPath(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }
        for (Path base : skillRoots()) {
            if (base == null) {
                continue;
            }
            Path result = findSkillInBase(base, skillName);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 在单个配置根目录下查找指定技能。
     *
     * @param base      配置根目录
     * @param skillName 技能名
     * @return 匹配路径；不存在返回 null
     */
    private Path findSkillInBase(Path base, String skillName) {
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
