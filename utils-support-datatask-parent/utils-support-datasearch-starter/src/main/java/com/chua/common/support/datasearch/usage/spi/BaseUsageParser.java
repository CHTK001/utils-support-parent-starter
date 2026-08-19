package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * UsageParser 基类 — 提供按天聚合公共逻辑
 *
 * @since 4.0.0.42
 */
public abstract class BaseUsageParser implements UsageParser {

    /** 日志 */
    protected final Logger log = LoggerFactory.getLogger(getClass());
    /** Day_fmt */
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 解析全量用量数据（子类实现）
     */
    @Override
    public abstract List<AiUsage> parseAll();

    @Override
    public List<AiUsage> parseDaily() {
        List<AiUsage> all = parseAll();
        if (all.isEmpty()) {
            return List.of();
        }
        return aggregateByDay(all);
    }

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
        /** DAY */
        private final String day;
        /** 提供者 */
        private final String provider;
        /** 输入tokens */
        private int inputTokens;
        /** 输出tokens */
        private int outputTokens;
        /** 总数tokens */
        private int totalTokens;
        /** 总数cost */
        private BigDecimal totalCost = BigDecimal.ZERO;
        /** 总数持续时间 */
        private long totalDuration;
        /** 数量 */
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
                return LocalDate.parse(d, DAY_FMT).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception e) {
                return 0L;
            }
        }
    }
}
