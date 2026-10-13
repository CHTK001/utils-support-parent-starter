package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.ai.skill.SkillDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * DSH (DeepSeek Harness) 离线技能提供者。
 *
 * <p>DSH 的技能落盘位置与其它 Agent 不同：它<b>不</b>放在 {@code ~/.dsh/skills} 下，
 * 而是统一收在用户主目录的 {@code ~/.agents/skills/&#60;技能名&#62;/SKILL.md}，
 * 项目级另见工作目录向上的 {@code .agents/skills}。因此本实现覆写
 * {@link #skillRoots()}，不再沿用基类"主目录 + 配置目录名"的约定。</p>
 *
 * <p>另有两点与基类默认行为不同，均由 DSH 的 SKILL.md 实际写法决定：</p>
 * <ul>
 *   <li>技能名优先取 front-matter 的 {@code name} 字段，而非目录名
 *       （如目录 {@code 0-ui-ux-pro-max} 的 name 为 {@code ui-ux-pro-max}）；</li>
 *   <li>描述可为 YAML 块标量（{@code description: >-} 后跟缩进续行），
 *       基类的单行解析会把 {@code >-} 本体当成描述。</li>
 * </ul>
 *
 * <p>{@code DSH_HOME} 只影响会话转录（即用量数据），技能目录恒定在 {@code ~/.agents}，
 * 故此处不读该变量。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("dsh")
public class DshSkillOfflineProvider extends AbstractAgentSkillOfflineProvider {

    /**
     * DSH 技能根目录（相对用户主目录或工作目录）
     */
    private static final Path AGENTS_SKILLS = Paths.get(".agents", "skills");

    @Override
    protected String configDir() {
        return ".agents";
    }

    @Override
    public String name() {
        return "dsh";
    }

    /**
     * DSH 技能根目录。
     *
     * <p>返回全局 {@code ~/.agents/skills}；若当前工作目录向上的某一级存在
     * 项目级 {@code .agents/skills} 且与全局不同，则一并扫描。</p>
     *
     * @return 技能根目录列表
     */
    @Override
    protected List<Path> skillRoots() {
        Path global = USER_HOME.resolve(AGENTS_SKILLS);
        Path project = findProjectAgents();
        if (project != null && !project.equals(global)) {
            return List.of(global, project);
        }
        return List.of(global);
    }

    /**
     * DSH 的根目录本身就是技能目录。
     *
     * <p>基类默认在根目录下再找 {@code skills/rules/commands} 子目录，
     * 而 {@code ~/.agents/skills} 的下一层已经是各技能目录，故直接返回根目录本身。</p>
     *
     * @return 技能目录列表
     */
    @Override
    protected List<Path> skillDirectories() {
        return skillRoots().stream()
                .filter(Files::isDirectory)
                .toList();
    }

    /**
     * 自当前工作目录向上查找项目级 {@code .agents/skills}。
     *
     * @return 项目级技能目录；未找到返回 空
     */
    private Path findProjectAgents() {
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 64 && current != null; i++) {
            Path candidate = current.resolve(AGENTS_SKILLS);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 解析 DSH 的 SKILL.md。
     *
     * <p>技能名优先取 front-matter 的 {@code name}，描述支持 {@code &gt;-}/{@code |}
     * 块标量的缩进续行；两者缺失时分别回退到目录名与空串。</p>
     *
     * @param skillMdFile SKILL.md 文件路径
     * @param skillName   目录名（name 字段缺失时的回退值）
     * @return 技能定义；读取失败返回 空
     */
    @Override
    protected SkillDefinition parseSkill(Path skillMdFile, String skillName) {
        String content;
        try {
            content = Files.readString(skillMdFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        DshFrontMatter frontMatter = DshFrontMatter.parse(content);
        String name = frontMatter.name != null && !frontMatter.name.isBlank()
                ? frontMatter.name
                : skillName;
        String description = frontMatter.description != null ? frontMatter.description : "";
        return SkillDefinition.skill(name, description, skillMdFile.toString());
    }
}
