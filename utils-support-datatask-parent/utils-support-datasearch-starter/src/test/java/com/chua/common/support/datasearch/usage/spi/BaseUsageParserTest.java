package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link BaseUsageParser} 公共取数口径测试。
 *
 * <p>各解析器的时间、Token、列值取值都走这里的静态工具，一处算错会同时污染几十家数据源，
 * 尤其是"转录只落完成时刻"时的发起时刻回退。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
class BaseUsageParserTest {

    /**
     * 一个只回吐固定记录的子类，用于验证按天聚合与惰性流包装
     */
    private static final class StubParser extends BaseUsageParser {

        /**
         * 待回吐的记录
         */
        private final List<AiUsage> records;

        /**
         * 存根解析器。
         *
         * @param records 待回吐记录
         */
        StubParser(List<AiUsage> records) {
            this.records = records;
        }

        @Override
        protected List<AiUsage> parseAll() {
            return records;
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    /**
     * 发起时刻回退：有耗时往前推，无耗时或非法耗时保持原时刻。
     */
    @Test
    @DisplayName("startTimeOf 只在耗时有效时前推")
    void backDatesStartTimeOnlyWithUsableDuration() {
        long end = Instant.parse("2026-09-20T10:00:03Z").toEpochMilli();
        assertEquals(end - 3_000L, BaseUsageParser.startTimeOf(end, 3_000L));
        assertEquals(end, BaseUsageParser.startTimeOf(end, null), "无耗时只能给结束时刻近似值");
        assertEquals(end, BaseUsageParser.startTimeOf(end, 0L));
        assertEquals(end, BaseUsageParser.startTimeOf(end, end + 1), "耗时大于时刻不能推出负数");
        assertEquals(0L, BaseUsageParser.startTimeOf(0L, 1_000L), "无效时刻不造时间");
    }

    /**
     * 时间字段三种量级都要认：epoch 秒、epoch 毫秒、ISO-8601（含带时区偏移）。
     */
    @Test
    @DisplayName("parseEpochMillis 兼容秒/毫秒/ISO")
    void recognizesAllEpochScales() {
        assertEquals(1_700_000_000_000L, BaseUsageParser.parseEpochMillis("1700000000"));
        assertEquals(1_700_000_000_123L, BaseUsageParser.parseEpochMillis("1700000000123"));
        assertEquals(Instant.parse("2026-09-20T10:00:00Z").toEpochMilli(),
                BaseUsageParser.parseEpochMillis("2026-09-20T10:00:00Z"));
        assertEquals(Instant.parse("2026-09-20T02:00:00Z").toEpochMilli(),
                BaseUsageParser.parseEpochMillis("2026-09-20T10:00:00+08:00"));
        assertEquals(0L, BaseUsageParser.parseEpochMillis("not-a-time"));
        assertEquals(0L, BaseUsageParser.parseEpochMillis(null));
        assertEquals(0L, BaseUsageParser.parseInstantToMillis("  "));
    }

    /**
     * 日期串按当天零点换算，非法日期返回 0。
     */
    @Test
    @DisplayName("parseDayStartToMillis 取当天零点")
    void convertsDayToStartOfDay() {
        long expected = LocalDate.parse("2026-09-20")
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, BaseUsageParser.parseDayStartToMillis("2026-09-20"));
        assertEquals(0L, BaseUsageParser.parseDayStartToMillis("2026/09/20"));
    }

    /**
     * Token 取值只在正数间回退，全非正时返回空而不是 0。
     */
    @Test
    @DisplayName("firstPositive 返回首个正数")
    void picksFirstPositiveValue() {
        assertNull(BaseUsageParser.firstPositive(0, -1, 0));
        assertEquals(Integer.valueOf(7), BaseUsageParser.firstPositive(0, 7, 9));
    }

    /**
     * 列值转换容忍 Number、字符串与脏值。
     */
    @Test
    @DisplayName("asInt/asLong/asDouble 容忍混合列型")
    void coercesMixedColumnValues() {
        assertEquals(42, BaseUsageParser.asInt(" 42 "));
        assertEquals(42, BaseUsageParser.asInt(42L));
        assertEquals(0, BaseUsageParser.asInt("n/a"));
        assertEquals(0, BaseUsageParser.asInt(null));
        assertEquals(42L, BaseUsageParser.asLong("42"));
        assertEquals(0.5d, BaseUsageParser.asDouble("0.5"));
        assertEquals("", BaseUsageParser.asStr(null));
    }

    /**
     * 候选路径取第一个真实存在的文件，全不存在时退回首个候选。
     *
     * @param dir 临时目录
     * @throws IOException 写夹具失败
     */
    @Test
    @DisplayName("firstExisting 逐个候选回退")
    void picksFirstExistingCandidate(@TempDir Path dir) throws IOException {
        Path existing = dir.resolve("second.db");
        Files.writeString(existing, "x");
        assertEquals(existing, BaseUsageParser.firstExisting(dir.resolve("missing.db"), existing));
        assertEquals(dir.resolve("missing.db"),
                BaseUsageParser.firstExisting(dir.resolve("missing.db"), dir.resolve("also-missing.db")),
                "全不存在时保留首个候选，维持不解析的原有行为");
    }

    /**
     * 未覆写 {@code streamAll} 的子类经基类惰性包装成流，并按天聚合汇总字段。
     */
    @Test
    @DisplayName("streamAll 包装 parseAll 且按天聚合")
    void streamsParseAllAndAggregatesByDay() {
        long dayOne = Instant.parse("2026-09-20T08:00:00Z").toEpochMilli();
        long dayTwo = Instant.parse("2026-09-21T08:00:00Z").toEpochMilli();
        List<AiUsage> raw = List.of(
                record(dayOne, 100, 10, new BigDecimal("0.1"), 5_000L),
                record(dayOne, 200, 20, new BigDecimal("0.2"), 7_000L),
                record(dayTwo, 300, 30, new BigDecimal("0.3"), 9_000L));
        StubParser parser = new StubParser(raw);

        List<AiUsage> streamed = parser.streamAll().collectList().block();
        assertEquals(3, streamed == null ? 0 : streamed.size(), "桥接流不得吞记录");

        List<AiUsage> days = parser.aggregateByDay(raw);
        assertEquals(2, days.size());
        AiUsage first = days.stream()
                .filter(u -> "2-calls".equals(u.getFinishReason()))
                .findFirst().orElseThrow();
        assertEquals(Integer.valueOf(300), first.getInputTokens());
        assertEquals(Integer.valueOf(30), first.getOutputTokens());
        assertEquals(Long.valueOf(12_000L), first.getDurationMillis());
        assertEquals(0, new BigDecimal("0.3").compareTo(first.getTotalCost()));
        assertEquals("daily-" + LocalDate.ofInstant(Instant.ofEpochMilli(dayOne), ZoneId.systemDefault()),
                first.getRequestId(), "聚合行的日期应取自记录发起时刻");
        assertEquals(1, days.stream().filter(u -> "1-calls".equals(u.getFinishReason())).count());
    }

    /**
     * 拼一条用于聚合的用量记录。
     *
     * @param startTime  发起时刻
     * @param input      输入 Token
     * @param output     输出 Token
     * @param totalCost  费用
     * @param durationMs 耗时
     * @return 用量记录
     */
    private static AiUsage record(long startTime, int input, int output,
                                  BigDecimal totalCost, long durationMs) {
        return AiUsage.builder()
                .provider("stub")
                .model("m")
                .startTime(startTime)
                .inputTokens(input)
                .outputTokens(output)
                .totalCost(totalCost)
                .durationMillis(durationMs)
                .build();
    }
}
