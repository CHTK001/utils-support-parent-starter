package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.QoderFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QoderUsageParser} 出数契约测试。
 *
 * <p>覆盖解析器的三件事：转录账单到 {@link AiUsage} 的字段映射、运行日志按
 * {@code request_id} 关联出的发起时刻与耗时、档位码经
 * {@link com.chua.common.support.datasearch.usage.spi.QoderModelCatalog} 还原为模型标识；
 * 以及"哪些行不出数"的跳过规则。</p>
 *
 * <p>夹具铺在 {@link com.chua.common.support.datasearch.usage.spi.UsageTestHome} 下，
 * 不读本机真实会话。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
class QoderUsageParserTest {

    /**
     * 单次解析的最长等待
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * 请求发起时刻
     */
    private static final long START = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli();

    /**
     * 响应完成时刻（发起后 3 秒）
     */
    private static final long COMPLETE = START + 3_000L;

    /**
     * 每个用例重铺夹具
     */
    @BeforeEach
    void setUp() {
        QoderFixture.reset();
    }

    /**
     * 运行日志能按请求号关联出发起时刻与耗时，转录时间戳只作兜底。
     */
    @Test
    @DisplayName("带运行日志的请求：startTime 取发起时刻、duration 取消耗差")
    void joinsRunLogTimelineByRequestId() {
        QoderFixture.transcript(
                QoderFixture.assistant(QoderFixture.instant(COMPLETE), "chatcmpl-1",
                        QoderFixture.TIER_QWEN_MAX, "end_turn", "req-1", 1_000, 800, 0, 200, 0.5d));
        QoderFixture.runLog(
                QoderFixture.event("model.request.started", QoderFixture.instant(START), "req-1"),
                QoderFixture.event("model.response.completed", QoderFixture.instant(COMPLETE), "req-1"));

        AiUsage usage = parseByMessageId("chatcmpl-1");

        assertEquals(START, usage.getStartTime(), "startTime 应是请求发起时刻而非转录完成时刻");
        assertEquals(Long.valueOf(COMPLETE - START), usage.getDurationMillis());
        assertEquals("qoder", usage.getProvider());
        assertEquals(QoderFixture.MODEL_QWEN_MAX, usage.getModel());
        assertEquals("end_turn", usage.getFinishReason());
        assertEquals(Integer.valueOf(1_000), usage.getInputTokens());
        assertEquals(Integer.valueOf(200), usage.getOutputTokens());
        assertEquals(Integer.valueOf(1_200), usage.getTotalTokens());
        assertEquals(Integer.valueOf(800), usage.getCacheTokens());
        assertEquals(0, new BigDecimal("0.5").compareTo(usage.getTotalCost()));
        assertEquals("CREDITS", usage.getCurrency());
    }

    /**
     * 运行日志缺该请求号时，退回转录时间戳且不给耗时。
     */
    @Test
    @DisplayName("关联不到运行日志：startTime 兜底为转录时刻、duration 为空")
    void fallsBackToTranscriptTimestamp() {
        QoderFixture.transcript(
                QoderFixture.assistant(QoderFixture.instant(COMPLETE), "chatcmpl-2",
                        QoderFixture.TIER_QWEN_MAX, "end_turn", "req-missing", 10, 0, 0, 5, 0.1d));
        QoderFixture.runLog(
                QoderFixture.event("model.request.started", QoderFixture.instant(START), "req-other"));

        AiUsage usage = parseByMessageId("chatcmpl-2");

        assertEquals(COMPLETE, usage.getStartTime().longValue());
        assertNull(usage.getDurationMillis(), "没有完成事件就不能凭空造耗时");
    }

    /**
     * 只有缓存写入量时按写入量出缓存字段；纯抵扣记录出估算 Token 并标记 estimated。
     */
    @Test
    @DisplayName("缓存写入单列；只有抵扣额时出估算 Token")
    void readsCacheWriteAndCreditOnlyRecords() {
        QoderFixture.transcript(
                QoderFixture.assistant(QoderFixture.instant(COMPLETE), "chatcmpl-3",
                        QoderFixture.TIER_QWEN_MAX, "end_turn", "req-3", 500, 0, 128, 50, 0.2d),
                QoderFixture.assistant(QoderFixture.instant(COMPLETE), "chatcmpl-4",
                        QoderFixture.TIER_QWEN_MAX, "end_turn", "req-4", 0, 0, 0, 0, 0.03285d));

        Map<String, AiUsage> byId = parse();
        AiUsage written = byId.get("chatcmpl-3");
        assertEquals(Integer.valueOf(128), written.getCacheTokens(), "命中为 0 时应退回缓存写入量");
        assertEquals(Integer.valueOf(550), written.getTotalTokens());

        AiUsage creditsOnly = byId.get("chatcmpl-4");
        assertTrue(creditsOnly.isEstimated(), "Token 全 0 时按正文估算，须标记 estimated");
        assertNotNull(creditsOnly.getInputTokens(), "估算输入须出数，不落 null");
        assertTrue(creditsOnly.getInputTokens() >= 100, "估算输入有 100 的下限");
        assertEquals(creditsOnly.getInputTokens() + creditsOnly.getOutputTokens(),
                creditsOnly.getTotalTokens().intValue());
        assertEquals(0, new BigDecimal("0.03285").compareTo(creditsOnly.getTotalCost()));
        assertEquals("CREDITS", creditsOnly.getCurrency(), "金额口径始终是平台抵扣额");
    }

    /**
     * 非 assistant 行、无 usage 行、零账单行都不产出记录。
     */
    @Test
    @DisplayName("跳过非响应行与零用量行")
    void skipsLinesWithoutBillableContent() {
        QoderFixture.transcript(
                QoderFixture.userLine(QoderFixture.instant(COMPLETE)),
                "{\"type\":\"assistant\",\"timestamp\":\"" + QoderFixture.instant(COMPLETE) + "\",\"message\":{}}",
                QoderFixture.assistant(QoderFixture.instant(COMPLETE), "chatcmpl-zero",
                        QoderFixture.TIER_QWEN_MAX, "end_turn", "req-0", 0, 0, 0, 0, 0d));

        assertTrue(parse().isEmpty(), "三条都不该出数");
    }

    /**
     * 每条出数都要满足 Token 口径：total = 输入 + 输出，缓存不超过输入。
     */
    @Test
    @DisplayName("全部出数满足 Token 口径契约")
    void keepsTokenContract() {
        QoderFixture.transcript(
                QoderFixture.assistant(QoderFixture.instant(COMPLETE), "chatcmpl-a",
                        QoderFixture.TIER_QWEN_MAX, "end_turn", "req-a", 1_000, 800, 0, 200, 0.5d),
                QoderFixture.assistant(QoderFixture.instant(COMPLETE), "chatcmpl-b",
                        QoderFixture.TIER_QWEN_FLASH, "tool_use", "req-b", 120, 0, 34, 56, 0.01d));

        List<AiUsage> records = parse().values().stream().toList();
        assertEquals(2, records.size());
        for (AiUsage usage : records) {
            assertNotNull(usage.getModel(), "档位码必须还原成模型标识");
            assertTrue(usage.getModel().startsWith("qwen"), "不应把档位码原样落库: " + usage.getModel());
            assertEquals(Integer.valueOf(usage.getInputTokens() + usage.getOutputTokens()),
                    usage.getTotalTokens(), "totalTokens 应为输入加输出");
            assertTrue(usage.getCacheTokens() <= usage.getInputTokens(),
                    "inputTokens 已含缓存，缓存量不可能更大");
        }
    }

    /**
     * 解析全部夹具并按 {@code message.id} 取单条。
     *
     * @param messageId 转录里的消息号
     * @return 对应记录
     */
    private AiUsage parseByMessageId(String messageId) {
        AiUsage usage = parse().get(messageId);
        assertNotNull(usage, "未解析出 " + messageId);
        return usage;
    }

    /**
     * 跑一次解析并按 {@code message.id} 建索引。
     *
     * @return 消息号到用量记录的映射
     */
    private Map<String, AiUsage> parse() {
        List<AiUsage> records = new QoderUsageParser().streamAll()
                .collectList().block(TIMEOUT);
        assertNotNull(records, "解析器未产出流");
        return records.stream().collect(Collectors.toMap(
                AiUsage::getRequestId, Function.identity(), (a, b) -> a));
    }
}
