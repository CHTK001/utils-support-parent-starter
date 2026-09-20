package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.UsageParser;
import com.chua.common.support.spi.ServiceProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 全量解析器字段体检探针。
 *
 * <p>逐个跑本机真实会话数据，统计每个解析器各字段的填充率，用于定位
 * Token 数、耗时、首字延迟等缺失项。只读取数据，不落库。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class UsageFieldCoverageProbe {

    /**
     * 单个解析器最多统计的记录数
     */
    private static final int SAMPLE = 4000;

    /**
     * 单个解析器最长耗时
     */
    private static final Duration BUDGET = Duration.ofSeconds(40);

    /**
     * 统计列名；末列为请求号重复数，不参与 {@link #CHECKS} 判定
     */
    private static final String[] LABELS = {
            "in", "out", "total", "cache", "c>in", "reason", "r>o", "badTot",
            "dur", "ttft", "start", "reqId", "finish", "cost",
            "dupReq"};

    /**
     * 重复请求号计数器所在下标
     */
    private static final int DUP_COL = LABELS.length;

    /**
     * {@code c>in} 列的计数器下标（LABELS 下标 4，c[0] 留给记录数）；该列非零即证明解析器按"输入不含缓存"出数
     */
    private static final int CACHE_GT_IN_COL = 5;

    /**
     * {@code r>o} 列的计数器下标（LABELS 下标 6）；非零即证明该源的推理量与输出互斥，解析器不该净出
     */
    private static final int REASON_GT_OUT_COL = 7;

    /**
     * {@code badTot} 列的计数器下标（LABELS 下标 7）；该列必须为 0，否则 totalTokens 未走统一口径
     */
    private static final int TOTAL_MISMATCH_COL = 8;

    /**
     * 逐列取值判定，与 {@link #LABELS} 除末列 {@code dupReq} 外一一对应
     */
    @SafeVarargs
    private static Predicate<AiUsage>[] checks(Predicate<AiUsage>... items) {
        return items;
    }

    /**
     * 各列判定：字段有值才计入
     */
    private static final Predicate<AiUsage>[] CHECKS = checks(
            u -> positive(u.getInputTokens()),
            u -> positive(u.getOutputTokens()),
            u -> positive(u.getTotalTokens()),
            u -> positive(u.getCacheTokens()),
            UsageFieldCoverageProbe::cacheExceedsInput,
            u -> positive(u.getReasoningTokens()),
            UsageFieldCoverageProbe::reasoningExceedsOutput,
            UsageFieldCoverageProbe::totalMismatch,
            u -> positive(u.getDurationMillis()),
            u -> positive(u.getFirstTokenLatencyMillis()),
            u -> positive(u.getStartTime()),
            u -> notBlank(u.getRequestId()),
            u -> notBlank(u.getFinishReason()),
            u -> u.getTotalCost() != null);

    /**
     * 执行探针
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        ServiceProvider<UsageParser> provider = ServiceProvider.of(UsageParser.class);
        Map<String, UsageParser> registered = provider.list();
        Map<String, Class<UsageParser>> declared = provider.listType();
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, Class<UsageParser>> entry : declared.entrySet()) {
            if (!registered.containsKey(entry.getKey())) {
                dropped.add(entry.getKey() + "=" + entry.getValue().getSimpleName());
            }
        }
        List<UsageParser> parsers = new ArrayList<>(registered.values());
        System.out.println("SPI 定义=" + declared.size() + " 已实例化=" + parsers.size()
                + " collect=" + provider.collect().size() + " 静默丢弃=" + dropped);
        StringBuilder header = new StringBuilder(String.format("%-24s %7s", "parser", "记录"));
        for (String label : LABELS) {
            header.append(String.format("%7s", label));
        }
        System.out.println(header);
        for (UsageParser parser : parsers) {
            report(parser);
        }
    }

    /**
     * 统计并打印单个解析器的字段填充率。
     *
     * @param parser 解析器
     */
    private static void report(UsageParser parser) {
        int[] c = new int[LABELS.length + 1];
        Set<String> seenRequests = new HashSet<>();
        String error = "";
        try {
            parser.streamAll().timeout(BUDGET).take(SAMPLE).toStream()
                    .forEach(usage -> tally(usage, c, seenRequests));
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
        }
        int total = c[0];
        if (total == 0) {
            System.out.printf("%-24s %7s  （本机无数据%s）%n", parser.name(), "0",
                    error.isEmpty() ? "" : " " + error);
            return;
        }
        StringBuilder row = new StringBuilder(String.format("%-24s %7d", parser.name(), total));
        for (int i = 0; i < LABELS.length; i++) {
            row.append(String.format("%6.0f%%", c[i + 1] * 100.0 / total));
        }
        if (c[CACHE_GT_IN_COL] > 0) {
            row.append(String.format("  输入不含缓存(%d行)", c[CACHE_GT_IN_COL]));
        }
        if (c[TOTAL_MISMATCH_COL] > 0) {
            row.append(String.format("  total≠输入+输出(%d行)", c[TOTAL_MISMATCH_COL]));
        }
        if (c[REASON_GT_OUT_COL] > 0) {
            row.append(String.format("  推理互斥(%d行)", c[REASON_GT_OUT_COL]));
        }
        System.out.println(row + (error.isEmpty() ? "" : "  ! " + error));
    }

    /**
     * 累加一条记录各字段的填充情况。
     *
     * @param usage        用量记录
     * @param c            计数器，下标 0 为记录数，{@link #DUP_COL} 为重复请求号数
     * @param seenRequests 本次采样已出现过的请求号
     */
    private static void tally(AiUsage usage, int[] c, Set<String> seenRequests) {
        c[0]++;
        String requestId = usage.getRequestId();
        if (notBlank(requestId) && !seenRequests.add(requestId)) {
            c[DUP_COL]++;
        }
        for (int i = 0; i < CHECKS.length; i++) {
            if (CHECKS[i].test(usage)) {
                c[i + 1]++;
            }
        }
    }

    /**
     * 数值是否为有效正值。
     *
     * @param value 数值，允许为 空
     * @return true 表示有值且大于 0
     */
    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    /**
     * 整型是否为有效正值。
     *
     * @param value 数值，允许为 空
     * @return true 表示有值且大于 0
     */
    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    /**
     * 推理量是否超过输出。
     *
     * <p>源把推理记成输出的明细时该情况不可能出现；一旦出现即说明这段数据里推理与输出互斥，
     * 解析器原样分列即可，不该再净出。</p>
     *
     * @param usage 用量记录
     * @return true 表示 {@code reasoningTokens} 严格大于 {@code outputTokens}
     */
    private static boolean reasoningExceedsOutput(AiUsage usage) {
        Integer reasoning = usage.getReasoningTokens();
        Integer output = usage.getOutputTokens();
        return reasoning != null && output != null && reasoning > output;
    }

    /**
     * 总令牌数是否违反 {@code totalTokens = inputTokens + outputTokens}。
     *
     * @param usage 用量记录
     * @return true 表示三个值齐备却对不上
     */
    private static boolean totalMismatch(AiUsage usage) {
        Integer total = usage.getTotalTokens();
        Integer input = usage.getInputTokens();
        Integer output = usage.getOutputTokens();
        return total != null && input != null && output != null && total != input + output;
    }

    /**
     * 缓存命中是否多于输入。
     *
     * <p>输入含缓存时缓存命中必然不超过输入，因此只要有一行更大，就说明该解析器的
     * {@code inputTokens} 未把缓存计进去，费用不能按"输入减去缓存"折算。</p>
     *
     * @param usage 用量记录
     * @return true 表示 {@code cacheTokens} 严格大于 {@code inputTokens}
     */
    private static boolean cacheExceedsInput(AiUsage usage) {
        Integer cache = usage.getCacheTokens();
        Integer input = usage.getInputTokens();
        return cache != null && input != null && cache > input;
    }

    /**
     * 字符串是否有内容。
     *
     * @param value 字符串，允许为 空
     * @return true 表示非空且非空白
     */
    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
