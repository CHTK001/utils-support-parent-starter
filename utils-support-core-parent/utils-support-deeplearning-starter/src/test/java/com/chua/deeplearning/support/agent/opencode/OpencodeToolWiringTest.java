package com.chua.deeplearning.support.agent.opencode;

import com.chua.common.support.ai.agent.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * opencode 适配层的工具面测试。
 *
 * <p>opencode 在独立进程内自管工具循环，进程内 Java 执行器无法回调，因此：
 * 注册工具必须显式失败；按名称裁剪工具必须真正写进 {@code opencode.json}
 * 的 {@code agent.<name>.tools}（用 {@code false} 关闭），而不是存下来就忘掉。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class OpencodeToolWiringTest {

    /**
     * 项目配置写入工具开关。
     *
     * @param dir 临时目录
     * @throws Exception 读写失败
     */
    @Test
    void agentSpecWritesToolSwitchesIntoConfig(@TempDir Path dir) throws Exception {
        OpencodeProjectConfig.in(dir)
                .agent(new OpencodeProjectConfig.AgentSpec("chua", OpencodeProjectConfig.MODE_PRIMARY,
                        null, "prompt", null, null, null,
                        Map.of("bash", Boolean.FALSE, "webfetch", Boolean.FALSE)))
                .write();

        JsonNode tools = new ObjectMapper()
                .readTree(Files.readString(dir.resolve(OpencodeProjectConfig.CONFIG_FILE)))
                .path("agent").path("chua").path("tools");

        assertFalse(tools.path("bash").asBoolean(true));
        assertFalse(tools.path("webfetch").asBoolean(true));
        assertEquals(2, tools.size());
    }

    /**
     * disableTool 必须真的落到 opencode.json。
     *
     * @param dir 临时目录
     * @throws Exception 读写失败
     */
    @Test
    void disableToolIsWrittenByApplyProjectConfig(@TempDir Path dir) throws Exception {
        OpencodeServerAgent agent = new OpencodeServerAgent();
        agent.disableTool("bash").disableTool("task");
        java.lang.reflect.Method apply = OpencodeServerAgent.class
                .getDeclaredMethod("applyProjectConfig", Path.class);
        apply.setAccessible(true);
        apply.invoke(agent, dir);

        JsonNode root = new ObjectMapper()
                .readTree(Files.readString(dir.resolve(OpencodeProjectConfig.CONFIG_FILE)));
        JsonNode agentNode = root.path("agent");
        assertEquals(1, agentNode.size(), "应只生成一个承载工具开关的主 agent");
        JsonNode tools = agentNode.path("chua").path("tools");
        assertFalse(tools.path("bash").asBoolean(true));
        assertFalse(tools.path("task").asBoolean(true));
    }

    /**
     * 注册进程内工具必须显式失败并给出替代做法。
     */
    @Test
    void toolRegistrationFailsExplicitly() {
        OpencodeServerAgent agent = new OpencodeServerAgent();
        AgentTool tool = AgentTool.builder()
                .name("echo")
                .description("回显")
                .executor(args -> "ok")
                .build();

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> agent.tool(tool));
        assertTrue(error.getMessage().contains("mcpServer"), "必须指向 MCP 替代做法: " + error.getMessage());
        assertTrue(agent.tools().isEmpty());
    }

    /**
     * modelOnly 不带工具开关时不应凭空生成 tools 段。
     *
     * @param dir 临时目录
     * @throws Exception 读写失败
     */
    @Test
    void modelOnlyDoesNotInventToolSection(@TempDir Path dir) throws Exception {
        OpencodeProjectConfig.in(dir)
                .agent(OpencodeProjectConfig.AgentSpec.modelOnly("reviewer", "anthropic/claude"))
                .write();

        JsonNode agentNode = new ObjectMapper()
                .readTree(Files.readString(dir.resolve(OpencodeProjectConfig.CONFIG_FILE)))
                .path("agent").path("reviewer");

        assertNotNull(agentNode.get("model"));
        assertFalse(agentNode.has("tools"));
        assertTrue(List.of("anthropic/claude").contains(agentNode.path("model").asText()));
    }
}
