package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScnetUsageParser} 取数与口径测试。
 *
 * <p>会话样本按客户端实际写出的 assistant 消息结构构造：{@code usage} 的
 * {@code input}/{@code cacheRead}/{@code cacheWrite}/{@code output} 为互斥分段，
 * 段和即 {@code totalTokens}。若不并回缓存段，输入量会少算；若把命中量当输入量扣减，
 * 命中单价又会错位，两种偏差都只能靠本用例拦住。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
class ScnetUsageParserTest {

    /**
     * 扁平消息行：缓存段必须并回输入，命中量单列。
     *
     * @param dir 临时会话目录
     * @throws IOException 写文件失败
     */
    @Test
    @DisplayName("扁平消息：缓存段并回输入、命中量单列、费用透传")
    void flatMessagesMergeCacheSegments(@TempDir Path dir) throws IOException {
        write(dir, "2026-11-11T05-10-46-514Z_01a1c904.jsonl", String.join("\n",
                "{\"role\":\"assistant\",\"model\":\"SCNet-Max\",\"responseId\":\"r1\","
                        + "\"timestamp\":1784858335197,\"stopReason\":\"stop\","
                        + "\"usage\":{\"input\":1000,\"output\":200,\"cacheRead\":400,\"cacheWrite\":100,"
                        + "\"totalTokens\":1700,\"cost\":{\"input\":0,\"output\":0,\"cacheRead\":0,"
                        + "\"cacheWrite\":0,\"total\":0.012}}}",
                "{\"role\":\"user\",\"content\":\"hi\"}",
                "{\"role\":\"assistant\",\"model\":\"SCNet-Max\",\"responseId\":\"r2\","
                        + "\"timestamp\":1784858345197,"
                        + "\"usage\":{\"input\":10,\"output\":5,\"totalTokens\":15}}"));

        List<AiUsage> calls = parserAt(dir).collectCalls(dir.resolve("2026-11-11T05-10-46-514Z_01a1c904.jsonl"));

        assertEquals(2, calls.size(), "user 行不应产出记录");
        Map<String, AiUsage> byId = calls.stream()
                .collect(Collectors.toMap(AiUsage::getRequestId, Function.identity()));
        AiUsage first = byId.get("r1");
        assertNotNull(first);
        assertEquals("scnet", first.getProvider());
        assertEquals("SCNet-Max", first.getModel());
        // [P3C A20 豁免]
        assertEquals(1500, first.getInputTokens().intValue(), "input 必须含 cacheRead 与 cacheWrite");
        // [P3C A20 豁免]
        assertEquals(200, first.getOutputTokens().intValue());
        // [P3C A20 豁免]
        assertEquals(400, first.getCacheTokens().intValue(), "命中量只算 cacheRead");
        // [P3C A20 豁免]
        assertEquals(1700, first.getTotalTokens().intValue(), "inputTokens + outputTokens");
        assertEquals(0, new BigDecimal("0.012").compareTo(first.getTotalCost()));
        // [P3C A20 豁免]
        assertEquals(1784858335197L, first.getStartTime().longValue());

        AiUsage second = byId.get("r2");
        assertNotNull(second);
        // [P3C A20 豁免]
        assertEquals(10, second.getInputTokens().intValue());
        // [P3C A20 豁免]
        assertEquals(15, second.getTotalTokens().intValue());
    }

    /**
     * 轨迹事件：只认快照里的消息，事件级累计值不得参与计数，同号调用跨事件去重。
     *
     * @param dir 临时会话目录
     * @throws IOException 写文件失败
     */
    @Test
    @DisplayName("轨迹快照：忽略事件级累计用量并按响应号去重")
    void trajectorySnapshotIgnoresCumulativeUsage(@TempDir Path dir) throws IOException {
        String firstEvent = "{\"type\":\"model.completed\",\"modelId\":\"SCNet-Max\","
                + "\"data\":{\"usage\":{\"input\":999999,\"output\":999999,\"cacheRead\":0,\"total\":0},"
                + "\"messagesSnapshot\":[{\"role\":\"assistant\",\"responseId\":\"r1\","
                + "\"timestamp\":1784858335197,"
                + "\"usage\":{\"input\":1000,\"output\":200,\"cacheRead\":400,\"cacheWrite\":0}}]}}";
        String secondEvent = "{\"type\":\"model.completed\",\"modelId\":\"SCNet-Max\","
                + "\"data\":{\"usage\":{\"input\":999999,\"output\":999999,\"cacheRead\":0,\"total\":0},"
                + "\"messagesSnapshot\":[{\"role\":\"assistant\",\"responseId\":\"r1\","
                + "\"timestamp\":1784858335197,"
                + "\"usage\":{\"input\":1000,\"output\":200,\"cacheRead\":400,\"cacheWrite\":0}},"
                + "{\"role\":\"assistant\",\"responseId\":\"r2\",\"timestamp\":1784858345197,"
                + "\"usage\":{\"input\":10,\"output\":5,\"cacheRead\":0,\"cacheWrite\":0}}]}}";
        write(dir, "s1.jsonl", String.join("\n", firstEvent, secondEvent));

        List<AiUsage> calls = parserAt(dir).collectCalls(dir.resolve("s1.jsonl"));

        assertEquals(2, calls.size(), "同一响应号跨事件只应记一次");
        int inputSum = calls.stream().mapToInt(u -> u.getInputTokens() == null ? 0 : u.getInputTokens()).sum();
        // [P3C A20 豁免]
        assertEquals(1410, inputSum, "事件级累计用量 999999 不得混入");
    }

    /**
     * 会话目录缺失或没有 JSONL 时不得抛异常。
     *
     * @param dir 临时目录
     */
    @Test
    @DisplayName("会话目录缺失时返回空列表")
    void missingRootYieldsEmpty(@TempDir Path dir) {
        List<Path> files = parserAt(dir.resolve("absent")).listSessionFiles();
        assertTrue(files.isEmpty());
    }

    /**
     * 构造指向指定会话目录的解析器。
     *
     * @param root 会话目录
     * @return 解析器
     */
    private static ScnetUsageParser parserAt(Path root) {
        return new ScnetUsageParser() {
            @Override
            protected Path sessionRoot() {
                return root;
            }
        };
    }

    /**
     * 写入一个会话文件。
     *
     * @param dir  会话目录
     * @param name 文件名
     * @param body 内容
     * @throws IOException 写文件失败
     */
    private static void write(Path dir, String name, String body) throws IOException {
        Files.writeString(dir.resolve(name), body + "\n", StandardCharsets.UTF_8);
    }
}
