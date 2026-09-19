package com.chua.deeplearning.support.agent;

import com.chua.common.support.ai.agent.AgentEvent;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.deeplearning.support.engine.CliModelRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link OpencodeAgent} 的 {@code stream=true} 端到端集成测试：真实拉起本地 opencode，
 * 覆盖逐事件 NDJSON 回调与 {@code maxToolIterations} 看门狗强杀进程路径。
 *
 * <p>默认不运行。仅当 {@code -Dopencode.it=true} 时启用。可用系统属性：</p>
 * <ul>
 *   <li>{@code -Ddeeplearning.cli.opencode.bin=<exe 路径>}：显式指定二进制，缺省走 PATH/缓存/下载</li>
 *   <li>{@code -Dopencode.it.model=<provider/model>}：指定模型，缺省用 opencode 自身默认模型</li>
 *   <li>{@code -Dopencode.it.timeout=<秒>}：单次执行超时，默认 180</li>
 * </ul>
 *
 * <p>opencode 会在 {@code --dir} 内自治读写文件、执行命令，故一律用 {@link TempDir} 沙箱，绝不指向仓库。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@EnabledIfSystemProperty(named = "opencode.it", matches = "true")
class OpencodeAgentStreamIT {

    /**
     * 测试：stream=true 时逐事件回调 onEvent，回调数与响应事件数一致，且累计到用量。
     *
     * @param dir 临时工作目录（沙箱）
     */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void streamTrue_emitsEventsThroughCallback(@TempDir Path dir) {
        List<AgentEvent> streamed = new ArrayList<>();
        AgentResponse resp = runStreamed(dir, streamed);

        assertFalse(streamed.isEmpty(), "stream=true 必须逐事件回调 onEvent");
        assertEquals(resp.getEvents().size(), streamed.size(), "回调事件数应与响应事件数一致");
        assertTrue(resp.getEvents().stream().anyMatch(e -> "step_finish".equals(e.type())),
                "至少应解析出一条 step_finish 事件");
        assertNotNull(resp.getUsage(), "step_finish 应累计出用量");
    }

    /**
     * 测试：maxToolIterations=1 时首个 step_finish 触发看门狗，进程被强杀并标记 capped。
     *
     * @param dir 临时工作目录（沙箱）
     */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void maxToolIterations_forceKillsProcessLive(@TempDir Path dir) {
        OpencodeAgent ag = newAgent(dir).maxToolIterations(1);
        AgentResponse resp = ag.runStream(prompt(), e -> {
        });

        assertEquals(Boolean.TRUE, resp.getMetadata().get("cappedByMaxToolIterations"),
                "maxToolIterations 看门狗应在首个 step_finish 触发并强杀进程");
        assertFalse(resp.getEvents().isEmpty(), "终止前已采集的事件应保留");
    }

    /**
     * 以流式跑一次最小提示，返回响应并把事件收集到给定列表。
     *
     * @param dir 临时工作目录
     * @param sink 事件收集列表
     * @return 汇总响应
     */
    private AgentResponse runStreamed(Path dir, List<AgentEvent> sink) {
        return newAgent(dir).runStream(prompt(), sink::add);
    }

    /**
     * 构造一个指向临时目录、按系统属性配置超时/模型的代理。
     *
     * @param dir 临时工作目录
     * @return 配置好的代理
     */
    private static OpencodeAgent newAgent(Path dir) {
        OpencodeAgent ag = new OpencodeAgent()
                .workDir(dir.toString())
                .timeoutSeconds(Long.getLong("opencode.it.timeout", 180L));
        String model = System.getProperty("opencode.it.model");
        if (model != null && !model.isBlank()) {
            ag.model(model.trim());
        }
        return ag;
    }

    /**
     * 取测试提示（可用 {@code -Dopencode.it.prompt} 覆盖），默认要求只回一个词以避免副作用。
     *
     * @return 提示文本
     */
    private static String prompt() {
        String p = System.getProperty("opencode.it.prompt");
        return (p != null && !p.isBlank()) ? p : "Reply with exactly the word OK and nothing else.";
    }

    /**
     * 测试前置：若无法解析到 opencode 二进制，给出明确指引而非深层异常。
     */
    @Test
    void resolveBinaryOrExplain() {
        try {
            Path exe = CliModelRunner.locate(CliModelRunner.opencode());
            assertTrue(Files.isExecutable(exe), "解析到的路径不可执行: " + exe);
        } catch (Exception e) {
            fail("未解析到 opencode 二进制。请安装或用 -Ddeeplearning.cli.opencode.bin=<exe 路径> 指定。原因: "
                    + e.getMessage());
        }
    }
}
