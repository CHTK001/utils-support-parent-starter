package com.chua.deeplearning.support.agentscope;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.memory.MemoryConfig;
import com.chua.common.support.ai.memory.MemoryEntry;
import com.chua.common.support.ai.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentScopeAgent} 长期记忆接线测试。
 *
 * <p>此处只验证「配置真的落到 {@link MemoryManager}」：未配置时为 null，
 * 配置后拿到的是真实管理器，且存储目录随 workspace 走、读写可roundtrip。
 * 不触发模型调用。</p>
 *
 * <p>不使用 JUnit 的 {@code @TempDir}：Windows 上记忆存储会先建
 * {@code .agent/memory/memory} 两级目录，JUnit 的清理逻辑在此场景会
 * 报 {@code DirectoryNotEmptyException} 把用例打成 ERROR。这里自建
 * {@code target/} 下的目录并在 {@code finally} 里逐层删除。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class AgentScopeMemoryWiringTest {

    /**
     * 在 target 下建一个本次用例独占的目录。
     *
     * @return 目录路径
     * @throws IOException 建目录失败
     */
    private static Path newWorkDir() throws IOException {
        Path dir = Path.of("target", "as-memory-test", UUID.randomUUID().toString());
        return Files.createDirectories(dir).toAbsolutePath();
    }

    /**
     * 递归删除用例目录（删除失败不影响用例结论，目录位于 target 下）。
     *
     * @param dir 目录路径
     */
    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ig) {
                    // target 下的残留不构成失败
                }
            });
        } catch (IOException ig) {
            // 同上
        }
    }

    /**
     * 未给 memoryConfig、也没有 definition 时必须返回 null（未启用记忆）。
     */
    @Test
    void memoryIsNullWithoutConfigOrDefinition() {
        Agent agent = Agent.create("agentscope");
        try {
            assertNull(agent.getMemoryManager());
        } finally {
            agent.close();
        }
    }

    /**
     * definition 显式关闭记忆时也必须是 null，不能凭空建出存储目录。
     */
    @Test
    void memoryIsNullWhenDefinitionDisablesIt() {
        Agent agent = Agent.create("agentscope");
        try {
            agent.definition(AgentDefinition.builder()
                    .id("no-memory")
                    .instruction("不需要长期记忆")
                    .memory(false)
                    .build());
            assertNull(agent.getMemoryManager());
        } finally {
            agent.close();
        }
    }

    /**
     * 显式 memoryConfig 必须建出真实管理器，且检索能读回刚写入的内容。
     *
     * @throws IOException 目录操作失败
     */
    @Test
    void memoryConfigCreatesRealManagerAndRoundTrips() throws IOException {
        Path workDir = newWorkDir();
        Agent agent = Agent.create("agentscope");
        try {
            agent.definition(AgentDefinition.builder()
                    .id("memory-agent")
                    .instruction("记住用户偏好")
                    .build());
            agent.memoryConfig(MemoryConfig.builder()
                    .workspace(workDir.toString())
                    .build());

            MemoryManager manager = agent.getMemoryManager();
            assertNotNull(manager, "显式配置 memoryConfig 后必须返回真实管理器");
            assertSame(manager, agent.getMemoryManager(), "管理器应复用同一实例");

            manager.saveFromConversation("用户: 我喜欢深色主题\n助手: 已记住",
                    "session-1", "memory-agent");

            List<MemoryEntry> hits = manager.search("深色主题", 5);
            assertEquals(1, hits.size(), "刚写入的记忆应能被检索到");
            assertTrue(hits.get(0).getContent().contains("深色主题"),
                    "检索结果内容不符: " + hits.get(0).getContent());
            assertEquals("session-1", hits.get(0).getSessionId());
        } finally {
            agent.close();
            deleteQuietly(workDir);
        }
    }

    /**
     * 只给 workspace、不给 memoryConfig 时，默认配置的记忆目录必须落在
     * {@code <workspace>/.agent/memory} 下，而不是进程当前目录。
     *
     * @throws IOException 读取目录失败
     */
    @Test
    void defaultConfigLandsUnderWorkspace() throws IOException {
        Path workDir = newWorkDir();
        Agent agent = Agent.create("agentscope");
        try {
            agent.definition(AgentDefinition.builder()
                    .id("workspace-agent")
                    .instruction("把记忆放在工作目录里")
                    .build());
            agent.workspace(workDir.toString());

            MemoryManager manager = agent.getMemoryManager();
            assertNotNull(manager, "definition 默认 memory=true 时应启用记忆");
            manager.saveFromConversation("用户: 记一条测试记忆\n助手: 好",
                    "s-2", "workspace-agent");

            Path memoryDir = workDir.resolve(".agent").resolve("memory").resolve("memory");
            assertTrue(Files.isDirectory(memoryDir),
                    "记忆目录应落在 workspace 下: " + memoryDir);
            try (var files = Files.list(memoryDir)) {
                assertTrue(files.findAny().isPresent(), "保存后记忆目录不应为空: " + memoryDir);
            }
        } finally {
            agent.close();
            deleteQuietly(workDir);
        }
    }
}
