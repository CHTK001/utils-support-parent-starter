package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
   * usageparser 基类 — 提供按天聚合公共逻辑。
 *
 * <p>子类实现 {@link #parseAll()} 从各自数据源读取原始用量记录，
 * 本基类提供 {@link #aggregateByDay(List)} 按天分组聚合的通用能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class BaseUsageParser implements UsageParser {

    /** 日志记录器 */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd"); // DAY_FMT

    /**
     * 遗留桥接：子类若以 {@link #parseAll()} 提供数据，经此惰性包装为响应式流；
      * 直接覆写 流全部() 的子类不受影响。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        return Flux.defer(() -> Flux.fromIterable(parseAll()));
    }

    /**
      * 阻塞式全量装载（可选覆写）：供未直接实现 流全部 的存量子类使用。
     *
     * @return 原始 aiusage 记录列表
     */
    protected List<AiUsage> parseAll() {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " 必须实现 parseAll() 或 streamAll()");
    }

    /**
     * 按行惰性读取文本文件（内存占用与总量无关）。
     *
     * @param file 文本文件
     * @return 行内容流；文件由 Flux.使用 负责关闭
     */
    protected static Flux<String> streamLines(java.nio.file.Path file) {
        return Flux.using(
                () -> java.nio.file.Files.newBufferedReader(file),
                reader -> Flux.fromStream(reader.lines()),
                reader -> {
                    try {
                        reader.close();
                    } catch (Exception ignored) {
                        // 忽略关闭异常
                    }
                });
    }

    /**
      * 解析 ISO-8601 时间字符串为 轮次 毫秒（子类通用工具）。
     *
     * <p>兼容形如 {@code 2026-08-24T02:21:53.998Z} 的 Instant 格式，
     * 解析失败返回 0L。</p>
     *
     * @param isoTimestamp ISO-8601 时间字符串
     * @return epoch 毫秒；入参为空或非法时返回 0L
     */
    protected static long parseInstantToMillis(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(isoTimestamp).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
      * 解析 yyyy-MM-dd 日期字符串为当天零点的 轮次 毫秒（子类通用工具）。
     *
     * @param dateStr 日期字符串
     * @return epoch 毫秒；入参为空或非法时返回 0L
     */
    protected static long parseDayStartToMillis(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return 0L;
        }
        try {
            return LocalDate.parse(dateStr, DAY_FMT)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 返回第一个非空白字符串（子类通用工具）。
     *
     * @param value    待检查的值
     * @param fallback 兜底值
     * @return value 非空白时返回 值，否则返回 降级
     */
    protected static String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }

    /**
     * 将数据库列值转换为 int（子类通用工具）。
     *
     * <p>兼容 Number、可解析的字符串；无法转换时返回 0。</p>
     *
     * @param value 原始列值
     * @return int 值
     */
    protected static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
 // 下降 through
            }
        }
        return 0;
    }

    /**
     * 将数据库列值转换为 long（子类通用工具）。
     *
     * @param value 原始列值
     * @return long 值
     */
    protected static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
 // 下降 through
            }
        }
        return 0L;
    }

    /**
     * 将数据库列值转换为 double（子类通用工具）。
     *
     * @param value 原始列值
     * @return double 值
     */
    protected static double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
 // 下降 through
            }
        }
        return 0.0d;
    }

    /**
     * 将数据库列值转换为非空字符串（子类通用工具）。
     *
     * @param value 原始列值
     * @return 字符串形式；null 转为空串
     */
    protected static String asStr(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
      * 将原始记录按天聚合，每天一条 AIusage 记录。
     *
     * @param records 原始用量记录列表
     * @return 按天聚合后的记录列表
     * @author CH
     * @since 4.0.0
     * @param d d
     /**
       * aggregatebyday。
      * @param records records
      * @return aggregateByDay的结果
      */
     * @param millis millis
      * @param d d
     /**
      * aggregateByDay。
      * @param records records
      * @return aggregateByDay的结果
      */
     */
    protected List<AiUsage> aggregateByDay(List<AiUsage> records) {
        Map<String, DayAggregator> dayMap = new LinkedHashMap<>();
        for (AiUsage usage : records) {
            String day = toDay(usage.getStartTime());
            dayMap.computeIfAbsent(day, d -> new DayAggregator(d, usage.getProvider())).add(usage);
        }
        List<AiUsage> result = new ArrayList<>(dayMap.size());
        for (DayAggregator agg : dayMap.values()) {
            result.add(agg.build());
        }
        return result;
    }

    private String toDay(Long millis) {
        if (millis == null) {
            return "";
        }
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .toLocalDate().format(DAY_FMT);
    }

    protected static class DayAggregator {

        private final String day; // day
        private final String provider; // 提供者
        private int inputTokens; // 输入令牌
        private int outputTokens; // 输出令牌
        private int totalTokens; // total令牌
        private BigDecimal totalCost = BigDecimal.ZERO; // totalcost
        private long totalDuration; // total持续时间
        private int count; // 数量

        DayAggregator(String day, String provider) {
            this.day = day;
            this.provider = provider;
        }

        void add(AiUsage u) {
            if (u.getInputTokens() != null) {
                inputTokens += u.getInputTokens();
            }
            if (u.getOutputTokens() != null) {
                outputTokens += u.getOutputTokens();
            }
            if (u.getTotalTokens() != null) {
                totalTokens += u.getTotalTokens();
            }
            if (u.getTotalCost() != null) {
                totalCost = totalCost.add(u.getTotalCost());
            }
            if (u.getDurationMillis() != null) {
                totalDuration += u.getDurationMillis();
            }
            count++;
        }

        AiUsage build() {
            return AiUsage.builder()
                    .provider(provider)
                    .model("daily-aggregated")
                    .requestId("daily-" + day)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .totalTokens(totalTokens)
                    .totalCost(totalCost)
                    .currency("USD")
                    .estimated(true)
                    .finishReason(count + "-calls")
                    .startTime(toMillis(day))
                    .durationMillis(totalDuration)
                    .build();
        }

        private long toMillis(String d) {
            try {
                return LocalDate.parse(d, DAY_FMT).atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
            } catch (Exception e) {
                return 0L;
            }
        }
    }
}
