package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.UsageTestHome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CopilotCliUsageParser} 出数契约测试。
 *
 * <p>该解析器的两个易错点：源里的 {@code outputTokens} 含推理量，按契约必须净出后单列；
 * {@code session.shutdown} 事件带的是完成时刻，有 {@code sessionStartTime} 时要取发起时刻。
 * 费用以 nano-AIU 计（1e-10 USD/单位），也要一并核对。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
class CopilotCliUsageParserTest {

    /**
     * 单次解析的最长等待
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * 会话发起时刻
     */
    private static final long SESSION_START = Instant.parse("2026-08-24T05:30:05.800Z").toEpochMilli();

    /**
     * 关闭事件时刻（响应完成）
     */
    private static final long SHUTDOWN_AT = Instant.parse("2026-08-24T05:30:35.800Z").toEpochMilli();

    /**
     * 单模型会话：净出推理量、缓存取读写较大值、费用按 nano-AIU 折算。
     */
    @Test
    @DisplayName("净出推理量并按 nano-AIU 计费")
    void netsReasoningOutOfOutput() {
        write("sess-net", """
                {"type":"session.shutdown","timestamp":"%s","data":{\
                "sessionStartTime":%d,"totalNanoAiu":386345000,"totalApiDurationMs":30285,\
                "modelMetrics":{"gpt-5-mini":{"usage":{"inputTokens":19849,"outputTokens":113,\
                "cacheReadTokens":5888,"cacheWriteTokens":0,"reasoningTokens":64},\
                "totalNanoAiu":386345000}}}}"""
                .formatted(Instant.ofEpochMilli(SHUTDOWN_AT), SESSION_START));

        AiUsage usage = parse("sess-net:gpt-5-mini");
        assertEquals(Integer.valueOf(19_849), usage.getInputTokens(), "inputTokens 已含缓存，按原值保留");
        assertEquals(Integer.valueOf(49), usage.getOutputTokens(), "输出应净掉 64 个推理 Token");
        assertEquals(Integer.valueOf(64), usage.getReasoningTokens());
        assertEquals(Integer.valueOf(19_898), usage.getTotalTokens());
        assertEquals(Integer.valueOf(5_888), usage.getCacheTokens());
        assertEquals(Long.valueOf(30_285L), usage.getDurationMillis());
        assertEquals(SESSION_START, usage.getStartTime().longValue());
        assertEquals(0, new BigDecimal("0.0386345").compareTo(usage.getTotalCost()));
        assertEquals("USD", usage.getCurrency());
        assertEquals("copilot-cli", usage.getProvider());
    }

    /**
     * 缺 sessionStartTime 时，用完成时刻回退一段 API 耗时。
     */
    @Test
    @DisplayName("无发起时刻时由完成时刻回退")
    void backDatesStartTimeWithoutSessionStart() {
        write("sess-back", """
                {"type":"session.shutdown","timestamp":"%s","data":{\
                "totalApiDurationMs":30285,\
                "modelMetrics":{"claude-x":{"usage":{"inputTokens":10,"outputTokens":5}}}}}"""
                .formatted(Instant.ofEpochMilli(SHUTDOWN_AT)));

        AiUsage usage = parse("sess-back:claude-x");
        assertEquals(SHUTDOWN_AT - 30_285L, usage.getStartTime().longValue());
        assertEquals(Long.valueOf(30_285L), usage.getDurationMillis());
        assertNull(usage.getTotalCost(), "源没给 nano-AIU 时不该造费用");
    }

    /**
     * 逐模型各出一条；无用量与零用量的模型不出数。
     */
    @Test
    @DisplayName("多模型会话逐条出数并丢弃零用量")
    void emitsOneRecordPerModel() {
        write("sess-multi", """
                {"type":"session.shutdown","timestamp":"%s","data":{\
                "sessionStartTime":%d,"totalApiDurationMs":1000,\
                "modelMetrics":{"m-hot":{"usage":{"inputTokens":30,"outputTokens":7}},\
                "m-zero":{"usage":{"inputTokens":0,"outputTokens":0,"cacheReadTokens":0}},\
                "m-cache":{"usage":{"inputTokens":8,"outputTokens":0,"cacheReadTokens":4}}}}}"""
                .formatted(Instant.ofEpochMilli(SHUTDOWN_AT), SESSION_START));

        List<AiUsage> records = stream();
        assertEquals(2, countSession(records, "sess-multi"), "零用量模型不应出数");
        assertTrue(records.stream().noneMatch(u -> "sess-multi:m-zero".equals(u.getRequestId())));
        AiUsage cacheOnly = parse("sess-multi:m-cache");
        assertEquals(Integer.valueOf(4), cacheOnly.getCacheTokens());
        assertEquals(Integer.valueOf(0), cacheOnly.getOutputTokens());
        assertEquals(Integer.valueOf(8), cacheOnly.getTotalTokens());
        assertNull(cacheOnly.getReasoningTokens(), "源未给推理量时不造 0");
    }

    /**
     * 非关闭事件与无关行不出数。
     */
    @Test
    @DisplayName("忽略非 session.shutdown 行")
    void ignoresOtherEvents() {
        write("sess-skip", """
                {"type":"user.message","timestamp":"%s","data":{"text":"hi"}}\
                """.formatted(Instant.ofEpochMilli(SHUTDOWN_AT)));

        assertEquals(0, countSession(stream(), "sess-skip"));
    }

    /**
     * 写一个会话的事件文件。
     *
     * @param sessionId 会话目录名
     * @param lines     事件 JSON（单行或多行）
     */
    private static void write(String sessionId, String lines) {
        UsageTestHome.write(".copilot/session-state/" + sessionId + "/events.jsonl",
                lines.replace('\n', ' ') + "\n");
    }

    /**
     * 跑一次解析。
     *
     * @return 用量记录列表
     */
    private static List<AiUsage> stream() {
        List<AiUsage> records = new CopilotCliUsageParser().streamAll()
                .collectList().block(TIMEOUT);
        return records == null ? List.of() : records;
    }

    /**
     * 按请求号取单条。
     *
     * @param requestId 请求号（会话名冒号拼模型名）
     * @return 对应记录
     */
    private static AiUsage parse(String requestId) {
        Optional<AiUsage> found = stream().stream()
                .filter(u -> requestId.equals(u.getRequestId())).findFirst();
        assertTrue(found.isPresent(), "未解析出 " + requestId);
        return found.get();
    }

    /**
     * 统计某个会话产出的记录数。
     *
     * @param records   全部记录
     * @param sessionId 会话目录名
     * @return 记录数
     */
    private static int countSession(List<AiUsage> records, String sessionId) {
        return (int) records.stream()
                .filter(u -> u.getRequestId() != null && u.getRequestId().startsWith(sessionId + ":"))
                .count();
    }
}
