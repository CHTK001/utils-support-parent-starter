package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.AiUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.chua.common.support.utils.StringUtils;

/**
 * UsageParser 基类 — 提供多目录解析与按天聚合公共逻辑。
 *
 * <p>子类按单个解析根目录实现 {@link #streamHome(Path)} 或 {@link #parseHome(Path)}
 * 从各自数据源读取原始用量记录；本基类负责把配置的多个根目录逐个串行解析
 * （{@link #streamAll()} 模板方法），并提供 {@link #aggregateByDay(List)} 按天分组聚合的通用能力。</p>
 *
 * <p>解析根目录来源：优先取 SPI 构造参数（逗号分隔的多个目录），
 * 未提供时回退当前用户主目录 {@code user.home}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class BaseUsageParser implements UsageParser {

    /**
     * 日志记录器
    */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 默认解析根目录：当前用户主目录（未配置时的兜底，一定存在）。
     */
    private static final Path DEFAULT_USER_HOME = Paths.get(System.getProperty("user.home", "."));

    /**
     * 解析根目录列表（至少一项，去重保序，不可变）。
     */
    private final List<Path> userHomes;

    /**
     * 默认构造：解析当前用户主目录。
     */
    protected BaseUsageParser() {
        this(null);
    }

    /**
     * 指定解析根目录构造。
     *
     * <p>SPI 场景由服务框架按构造参数精确匹配实例化；逗号分隔多个目录，
     * 空白项忽略，全部为空时回退当前用户主目录。</p>
     *
     * @param userHomes 逗号分隔的用户主目录列表，允许为 null
     */
    protected BaseUsageParser(String userHomes) {
        this.userHomes = parseUserHomes(userHomes);
    }

    /**
     * 解析目录列表：逗号分隔、去空白、按出现顺序去重；为空时回退默认用户主目录。
     *
     * @param csv 逗号分隔的目录列表，允许为 null/空
     * @return 不可变目录列表，至少一项
     */
    private static List<Path> parseUserHomes(String csv) {
        List<Path> homes = new ArrayList<>();
        if (StringUtils.isNotBlank(csv)) {
            for (String part : csv.split(",")) {
                if (StringUtils.isNotBlank(part)) {
                    Path path = Paths.get(part.trim());
                    if (!homes.contains(path)) {
                        homes.add(path);
                    }
                }
            }
        }
        if (homes.isEmpty()) {
            homes.add(DEFAULT_USER_HOME);
        }
        return List.copyOf(homes);
    }

    /**
     * 获取解析根目录列表。
     *
     * @return 不可变目录列表，至少一项
     */
    protected List<Path> userHomes() {
        return userHomes;
    }

    /**
     * 获取首个解析根目录（单目录场景的便捷访问器）。
     *
     * @return 首个解析根目录
     */
    protected Path home() {
        return userHomes.get(0);
    }

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * epoch 秒转毫秒的系数
     */
    private static final long EPOCH_SECONDS_TO_MILLIS = 1000L;

    /**
     * 秒/毫秒量级门限：epoch 秒在 2001 年之后仍小于 1e12，超过该量级即按毫秒解释
     */
    private static final long EPOCH_MILLIS_FLOOR = 1_000_000_000_000L;

    /**
     * 响应式流式入口：把配置的多个解析根目录逐个串行解析。
     *
     * <p>串行 {@code Flux.concat} 保证单个目录的流完整订阅完成后再进入下一个目录，
     * 子类的跨轮次游标/状态在目录之间不会被并发踩踏。</p>
     */
    @Override
    public Flux<AiUsage> streamAll() {
        return Flux.defer(() -> {
            List<Path> homes = userHomes;
            if (homes.size() == 1) {
                return streamHome(homes.get(0));
            }
            List<Flux<AiUsage>> parts = new ArrayList<>(homes.size());
            for (Path home : homes) {
                parts.add(streamHome(home));
            }
            return Flux.concat(parts);
        });
    }

    /**
     * 按单个解析根目录流式装载（子类覆写点）。
     *
     * <p>默认桥接到 {@link #parseHome(Path)}；需要流式/分页装载的子类覆写本方法。</p>
     *
     * @param home 解析根目录
     * @return 用量记录流
     */
    protected Flux<AiUsage> streamHome(Path home) {
        return Flux.fromIterable(parseHome(home));
    }

    /**
     * 按单个解析根目录阻塞式装载（子类覆写点）。
     *
     * <p>默认桥接到 {@link #parseAll()}；未迁移的存量子类继续走旧入口。</p>
     *
     * @param home 解析根目录
     * @return 原始 AiUsage 记录列表
     */
    protected List<AiUsage> parseHome(Path home) {
        return parseAll();
    }

    /**
     * 阻塞式全量装载（遗留兼容入口）：未迁移的存量子类可覆写；
     * 已迁移的子类应覆写 {@link #parseHome(Path)} 或 {@link #streamHome(Path)}。
     *
     * @return 原始 AiUsage 记录列表
     */
    protected List<AiUsage> parseAll() {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " 必须实现 parseHome()/streamHome() 或 parseAll()");
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
     * <p>兼容形如 {@code 2026-08-24T02:21:53.998Z} 的 Instant 格式与
     * {@code 2026-09-18T17:43:53.680+08:00} 的带时区偏移格式（qoder 运行日志用后者），
     * 解析失败返回 0L。</p>
     *
     * @param isoTimestamp ISO-8601 时间字符串
     * @return epoch 毫秒；入参为空或非法时返回 0L
     */
    protected static long parseInstantToMillis(String isoTimestamp) {
        if (StringUtils.isBlank(isoTimestamp)) {
            return 0L;
        }
        try {
            return OffsetDateTime.parse(isoTimestamp).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 返回第一个真实存在的候选路径（子类通用工具）。
     *
     * <p>数据目录常被告警级环境变量改写：本机 {@code XDG_DATA_HOME=E:\OpenCodeData} 里没有
     * {@code kilo/kilo.db}，而 {@code ~/.local/share/kilo/kilo.db} 与 {@code ~/.kilo/kilo.db}
     * 都有同一份数据——只认一个候选就会整表丢数。全部候选都不存在时返回首个候选，
     * 保持"路径不存在即不解析"的原有行为。</p>
     *
     * @param candidates 按优先级排列的候选路径
     * @return 第一个存在的候选；都不存在时返回首个候选
     */
    protected static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return candidates[0];
    }

    /**
     * 把结束时刻折算为请求发起时刻（子类通用工具）。
     *
     * <p>{@code AiUsage.startTime} 的契约是客户端发出请求的时刻，而部分转录只落盘
     * 响应完成的那一刻（atomcode 的 {@code ts}、gemini-cli 与 codebuddy 的回答行）。
     * 能拿到本次耗时时就回退到起点，让 {@code startTime + durationMillis} 覆盖整次请求；
     * 拿不到耗时时只能给出结束时刻这一近似值。</p>
     *
     * @param endMillis      结束时刻（epoch 毫秒），缺失时为 0
     * @param durationMillis 本次请求耗时，未知时 null
     * @return 发起时刻；入参时刻无效时返回 0
     */
    protected static long startTimeOf(long endMillis, Long durationMillis) {
        if (endMillis <= 0L) {
            return 0L;
        }
        if (durationMillis == null || durationMillis <= 0L || durationMillis >= endMillis) {
            return endMillis;
        }
        return endMillis - durationMillis;
    }

    /**
     * 把任意口径的时间值换算为 epoch 毫秒（子类通用工具）。
     *
     * <p>各 CLI 的转录/库里时间字段口径不一：epoch 秒、epoch 毫秒、ISO-8601 字符串
     * 都出现过，逐秒取整还会被误判成毫秒，因此按数值位数区分量级。</p>
     *
     * @param raw 时间原始值
     * @return epoch 毫秒；无法识别时返回 0L
     */
    protected static long parseEpochMillis(String raw) {
        if (StringUtils.isBlank(raw)) {
            return 0L;
        }
        String text = raw.trim();
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                long value = Long.parseLong(text);
                return value > EPOCH_MILLIS_FLOOR ? value : value * EPOCH_SECONDS_TO_MILLIS;
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return parseInstantToMillis(text);
    }

    /**
     * 解析 yyyy-MM-dd 日期字符串为当天零点的 epoch 毫秒（子类通用工具）。
     *
     * @param dateStr 日期字符串
     * @return epoch 毫秒；入参为空或非法时返回 0L
     */
    protected static long parseDayStartToMillis(String dateStr) {
        if (StringUtils.isBlank(dateStr)) {
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
        if (StringUtils.isNotBlank(value)) {
            return value;
        }
        return fallback;
    }

    /**
     * 返回第一个正数（子类通用工具）。
     *
     * <p>用于"读不到 A 口径就退回 B 口径"的令牌取值。写成 {@code a > 0 ? a : b > 0 ? b : null}
     * 的嵌套三元会被判为数值条件表达式而对 {@code null} 拆箱，取值时抛 NPE，故统一走本方法。</p>
     *
     * @param values 候选值，按优先级排列
     * @return 第一个正数；全部非正时返回 空
     */
    protected static Integer firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) {
                return Integer.valueOf(value);
            }
        }
        return null;
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
                // fall through
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
                // fall through
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
                // fall through
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
     * 将原始记录按天聚合，每天一条 AiUsage 记录。
     *
     * @param records 原始用量记录列表
     * @return 按天聚合后的记录列表
     */
    protected List<AiUsage> aggregateByDay(List<AiUsage> records) {
        Map<String, DayAggregator> dayMap = Wraps.linkedMap();
        for (AiUsage usage : records) {
            String day = toDay(usage.getStartTime());
            dayMap.computeIfAbsent(day, d -> new DayAggregator(d, usage.getProvider())).add(usage);
        }
        List<AiUsage> result = Wraps.list(dayMap.size());
        for (DayAggregator agg : dayMap.values()) {
            result.add(agg.build());
        }
        return result;
    }

    /**
     * 转为Day。
     *
     * @param millis 毫秒数，不允许为 null
     * @return 结果字符串
     */
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

        /**
         * Day Aggregator
         * @param day 字符串
         * @param provider 字符串
         * @return 返回值
        */
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

        /**
         * to Millis
         * @param d 字符串
         * @return 返回值
        */
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
