package com.chua.common.support.datasearch.skill.offline;

import com.chua.common.support.ai.skill.SkillDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DshSkillOfflineProvider} 取数口径测试。
 *
 * <p>DSH 的技能不在 {@code ~/.dsh/skills}，而在 {@code ~/.agents/skills}；
 * 且其 SKILL.md 的 front-matter 存在块标量写法、目录名可能与 {@code name} 字段不同。
 * 本测试按这三点铺夹具，防止实现退回"扫错目录 / 只读单行描述"的旧行为。</p>
 *
 * <p>{@code USER_HOME} 在类加载时固化，故必须在触碰提供者类之前改写
 * {@code user.home}（{@link #installFakeHome()} 标注 {@code @BeforeAll}，
 * 早于本类中任何提供者实例化）。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
class DshSkillOfflineProviderTest {

    /**
     * 假主目录，等价于提供者眼中的 {@code user.home}
     */
    private static Path home;

    /**
     * 原始 {@code user.home}，供用例结束后还原
     */
    private static String originalUserHome;

    /**
     * 把 {@code user.home} 指向空临时目录，避免读到本机真实技能。
     *
     * <p>该属性是 JVM 全局状态，必须在本类用例跑完后还原（见
     * {@link #restoreUserHome()}），否则同 JVM 内的其它测试会跟着看到假主目录——
     * 例如 {@code DshSessionDiagnosticTest} 会因此判定"本机无 DSH 数据"而被跳过。</p>
     *
     * @throws IOException 建目录失败
     */
    @BeforeAll
    static void installFakeHome() throws IOException {
        originalUserHome = System.getProperty("user.home");
        home = Files.createTempDirectory("dsh-skill-home");
        System.setProperty("user.home", home.toString());
    }

    /**
     * 还原 {@code user.home}。
     */
    @AfterAll
    static void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    /**
     * 在假主目录的 {@code .agents/skills} 下写入 {@code 技能目录/SKILL.md}。
     *
     * @param skills 技能目录名到 SKILL.md 内容的映射
     * @throws IOException 写文件失败
     */
    private static void writeSkills(Map<String, String> skills) throws IOException {
        Path root = home.resolve(".agents").resolve("skills");
        for (Map.Entry<String, String> e : skills.entrySet()) {
            Path dir = root.resolve(e.getKey());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), e.getValue(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 清空假主目录下的技能，保证用例间互不残留。
     *
     * @throws IOException 清理失败
     */
    private static void clearSkills() throws IOException {
        Path root = home.resolve(".agents");
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力清理
                }
            });
        }
    }

    /**
     * 新建提供者。
     *
     * @return 提供者实例
     */
    private static DshSkillOfflineProvider provider() {
        return new DshSkillOfflineProvider();
    }

    @Test
    @DisplayName("扫描 ~/.agents/skills 下的技能目录")
    void listsSkillsUnderAgentsDir() throws IOException {
        clearSkills();
        writeSkills(Map.of("bug-hunt", """
                ---
                name: bug-hunt
                description: 复现并定位问题。
                ---

                # Bug Hunt
                """));

        List<SkillDefinition> skills = provider().listAgentSkills();

        assertEquals(1, skills.size(), "应扫到 ~/.agents/skills 下的技能");
        assertEquals("bug-hunt", skills.getFirst().getName());
    }

    @Test
    @DisplayName("技能名取 front-matter 的 name 而非目录名")
    void prefersFrontMatterName() throws IOException {
        clearSkills();
        writeSkills(Map.of("0-ui-ux-pro-max", """
                ---
                name: ui-ux-pro-max
                description: UI/UX design intelligence.
                ---
                """));

        List<SkillDefinition> skills = provider().listAgentSkills();

        assertEquals(1, skills.size());
        assertEquals("ui-ux-pro-max", skills.getFirst().getName(),
                "目录名带序号前缀时，应使用 front-matter 的 name");
    }

    @Test
    @DisplayName("描述支持 >- 块标量的缩进续行")
    void parsesBlockScalarDescription() throws IOException {
        clearSkills();
        writeSkills(Map.of("alipay-payment-integration", """
                ---
                name: alipay-payment-integration
                description: >-
                  支付宝开放平台支付产品接入最佳实践。
                  当用户提到"接入支付宝"时使用此 Skill。
                ---
                """));

        List<SkillDefinition> skills = provider().listAgentSkills();

        assertEquals(1, skills.size());
        String desc = skills.getFirst().getDescription();
        assertTrue(desc.contains("支付宝开放平台支付产品接入最佳实践"),
                "块标量正文应被读入描述，实际: " + desc);
        assertTrue(desc.contains("接入支付宝"), "续行应被折叠进描述，实际: " + desc);
        assertFalse(desc.startsWith(">-"), "不得把块标量指示符当成描述，实际: " + desc);
    }

    @Test
    @DisplayName("name 缺失时回退到目录名")
    void fallsBackToDirName() throws IOException {
        clearSkills();
        writeSkills(Map.of("no-name-skill", """
                ---
                description: 只有描述。
                ---
                """));

        List<SkillDefinition> skills = provider().listAgentSkills();

        assertEquals(1, skills.size());
        assertEquals("no-name-skill", skills.getFirst().getName());
    }

    @Test
    @DisplayName("技能目录不存在时不报错且返回空")
    void emptyWhenNotInstalled() throws IOException {
        clearSkills();

        assertTrue(provider().listAgentSkills().isEmpty());
    }

    @Test
    @DisplayName("多个技能按目录名排序")
    void sortsByDirName() throws IOException {
        clearSkills();
        Map<String, String> skills = new LinkedHashMap<>();
        skills.put("zeta", "---\nname: zeta\ndescription: z\n---\n");
        skills.put("alpha", "---\nname: alpha\ndescription: a\n---\n");
        writeSkills(skills);

        List<String> names = provider().listAgentSkills().stream()
                .map(SkillDefinition::getName)
                .toList();

        assertEquals(List.of("alpha", "zeta"), names);
    }

    @Test
    @DisplayName("按技能名解析落盘路径")
    void resolvesSkillPath() throws IOException {
        clearSkills();
        writeSkills(Map.of("bug-hunt", "---\nname: bug-hunt\ndescription: d\n---\n"));

        Path resolved = provider().resolveSkillPath("bug-hunt");

        assertTrue(resolved != null && Files.isDirectory(resolved),
                "应解析到技能目录，实际: " + resolved);
    }

    @Test
    @DisplayName("带路径分隔符的技能名不得越出技能目录")
    void rejectsTraversalName() throws IOException {
        clearSkills();
        writeSkills(Map.of("bug-hunt", "---\nname: bug-hunt\ndescription: d\n---\n"));

        assertNull(provider().resolveSkillPath("../secret"));
        assertNull(provider().resolveSkillPath("..\\secret"));
    }
}
