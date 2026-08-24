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
 * UsageParser 基类 — 提供按天聚合公共逻辑。
 *
 * <p>子类实现 {@link #parseAll()} 从各自数据源读取原始用量记录，
 * 本基类提供 {@link #aggregateByDay(List)} 按天分组聚合的通用能力。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class BaseUsageParser implements UsageParser {

    /** Logger */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 解析全量用量数据（遗留桥接，子类实现）。
     *
     * <p>已从接口移除：新代码请重写 {@link #streamAll()} 实现真流式。
     * 基类通过下方桥接将本方法包装为响应式入口，保证存量实现可用。</p>
     *
     * @return 原始 AiUsage 记录列表
     */
    public abstract List<AiUsage> parseAll();

    /**
     * 遗留桥接：把旧的阻塞式 parseAll 包装为响应式流（惰性委托）。
     *
     * <p>数据量大的实现应直接重写 streamAll() 实现真流式以避免 OOM。</p>
     */
    @Override
    public Flux<AiUsage> streamAll() {
        return Flux.defer(() -> Flux.fromIterable(parseAll()));
    }

    /**
     * 按行惰性读取文本文件（内存占用与总量无关）。
     *
     * @param file 文本文件
     * @return 行内容流；文件由 Flux.using 负责关闭
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
     * 解析 ISO-8601 时间字符串为 epoch 毫秒（子类通用工具）。
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
     * 解析 yyyy-MM-dd 日期字符串为当天零点的 epoch 毫秒（子类通用工具）。
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
     * @return value 非空白时返回 value，否则返回 fallback
     */
    protected static String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }

    /**
     * 按天聚合（遗留便捷方法）：基于桥接的全量结果聚合，内存与天数成正比。
     *
     * @return 每天一条聚合记录
     */
    public List<AiUsage> parseDaily() {
        List<AiUsage> all = parseAll();
        if (all.isEmpty()) {
            return List.of();
        }
        return aggregateByDay(all);
    }

    /**
     * 将原始记录按天聚合，每天一条 AiUsage 记录。
     *
     * @param records 原始用量记录列表
     * @return 按天聚合后的记录列表
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

        private final String day;
        private final String provider;
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
        private BigDecimal totalCost = BigDecimal.ZERO;
        private long totalDuration;
        private int count;

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
