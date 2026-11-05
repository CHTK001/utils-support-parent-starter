// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.skywalking.support.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * SkyWalking 时间格式工具。
 * <p>
 * OAP 的 {@code Duration} 不是时间戳，而是「按步长格式化的字符串」：
 * </p>
 * <table border="1">
 *   <caption>步长与时间格式</caption>
 *   <tr><th>Step</th><th>格式</th><th>示例</th></tr>
 *   <tr><td>SECOND</td><td>yyyy-MM-dd HHmmss</td><td>2024-05-01 103055</td></tr>
 *   <tr><td>MINUTE</td><td>yyyy-MM-dd HHmm</td><td>2024-05-01 1030</td></tr>
 *   <tr><td>HOUR</td><td>yyyy-MM-dd HH</td><td>2024-05-01 10</td></tr>
 *   <tr><td>DAY</td><td>yyyy-MM-dd</td><td>2024-05-01</td></tr>
 * </table>
 * <p>
 * 时间是 <b>OAP 服务端本地时区</b> 的墙上时间。这里按查询方 JVM 默认时区格式化：
 * 二者通常同机同区；跨时区部署时最多偏差整体时间轴，不会导致查询失败。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class SkywalkingTimes {

    /**
     * 秒级步长
     */
    public static final String STEP_SECOND = "SECOND";

    /**
     * 分钟级步长
     */
    public static final String STEP_MINUTE = "MINUTE";

    /**
     * 小时级步长
     */
    public static final String STEP_HOUR = "HOUR";

    /**
     * 天级步长
     */
    public static final String STEP_DAY = "DAY";

    /**
     * 一天毫秒数
     */
    private static final long DAY_MS = 24L * 3600L * 1000L;

    private SkywalkingTimes() {
    }

    /**
     * 各步长的时间格式
     *
     * @param step 步长
     * @return 格式
     */
    private static DateTimeFormatter formatter(String step) {
        return switch (normalizeStep(step, 0, 0)) {
            case STEP_SECOND -> DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss", Locale.ROOT);
            case STEP_HOUR -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH", Locale.ROOT);
            case STEP_DAY -> DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
            default -> DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.ROOT);
        };
    }

    /**
     * 规范化步长：非法值按窗口长度推断
     *
     * @param step  期望步长，可为空
     * @param start 起始毫秒（用于推断，传 0 表示不推断）
     * @param end   结束毫秒
     * @return 规范化步长
     */
    public static String normalizeStep(String step, long start, long end) {
        if (step != null && !step.isBlank()) {
            String upper = step.trim().toUpperCase(Locale.ROOT);
            return switch (upper) {
                case STEP_SECOND, STEP_MINUTE, STEP_HOUR, STEP_DAY -> upper;
                default -> inferStep(start, end);
            };
        }
        return inferStep(start, end);
    }

    /**
     * 按窗口长度推断步长：30 分钟内用秒级、3 小时内用分钟级、3 天内用小时级，更长用天级
     *
     * @param start 起始毫秒
     * @param end   结束毫秒
     * @return 步长
     */
    private static String inferStep(long start, long end) {
        long window = end - start;
        if (window <= 0) {
            return STEP_MINUTE;
        }
        if (window <= 30L * 60L * 1000L) {
            return STEP_SECOND;
        }
        if (window <= 3L * 3600L * 1000L) {
            return STEP_MINUTE;
        }
        if (window <= 3L * DAY_MS) {
            return STEP_HOUR;
        }
        return STEP_DAY;
    }

    /**
     * 格式化时间点
     *
     * @param epochMillis 毫秒时间戳
     * @param step        步长
     * @return 字符串
     */
    public static String format(long epochMillis, String step) {
        return formatter(step).format(Instant.ofEpochMilli(epochMillis).atZone(zone()));
    }

    /**
     * 解析时间点字符串，失败返回 0。
     * <p>
     * <b>入参有两种形态，必须都支持：</b>
     * </p>
     * <ol>
     *   <li>{@code readMetricsValues} 返回的 {@code KVInt.id}：形如
     *       {@code service_cpm_202611050037_YXBtLWRlbW8tYQ==.1}，即
     *       {@code <指标名>_<时间桶>_<实体ID>}。<b>第一个下划线分片是指标名，
     *       不是时间，不能直接取。</b>完整的上游查询只查一个实体，所以时间桶恰好是
     *       唯一全数字的长度 8/10/12/14 的分片。</li>
     *   <li>查询侧自己回写的 {@code yyyy-MM-dd HHmm} 这种带分隔符的形态。</li>
     * </ol>
     * <p>
     * 时间桶本身是紧凑格式、<b>没有分隔符</b>（{@code yyyyMMddHHmmss} / {@code yyyyMMddHHmm} /
     * {@code yyyyMMddHH} / {@code yyyyMMdd}），不是查询 Duration 用的
     * {@code yyyy-MM-dd HHmm}。这一点在协议文件里看不出来，只能从 OAP 实现确认。
     * </p>
     * <p>
     * 解析不出来时返回 0，调用方据此丢弃该点 —— 所以格式一旦不匹配，
     * 表现是「指标图整张空白」而不是报错，最容易漏测。
     * </p>
     *
     * @param text 时间字符串
     * @param step 步长
     * @return 毫秒时间戳，失败返回 0
     */
    public static long parse(String text, String step) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        String value = text.trim();
        // 只要有形如 {指标名}_{时间桶}_{实体ID} 的 id，{时间桶} 就是分片法里的全数字分片。
        for (String part : value.split("_")) {
            Long bucket = parseCompact(part);
            if (bucket != null && bucket > 0L) {
                return bucket;
            }
        }
        // 1) 紧凑时间桶（OAP 存储格式）：yyyyMMddHHmmss / yyyyMMddHHmm / yyyyMMddHH / yyyyMMdd
        Long bucket = parseCompact(value);
        if (bucket != null) {
            return bucket;
        }
        // 2) 带分隔符的形态（查询侧自造 / 日志回显）
        for (String candidateStep : new String[]{normalizeStep(step, 0, 0), STEP_SECOND, STEP_MINUTE, STEP_HOUR, STEP_DAY}) {
            try {
                if (STEP_DAY.equals(candidateStep)) {
                    // DAY 的格式不带时分秒，LocalDateTime 解析必然失败，得用 LocalDate
                    return java.time.LocalDate
                            .parse(value, formatter(candidateStep))
                            .atStartOfDay(zone())
                            .toInstant()
                            .toEpochMilli();
                }
                return java.time.LocalDateTime
                        .parse(value, formatter(candidateStep))
                        .atZone(zone())
                        .toInstant()
                        .toEpochMilli();
            } catch (Exception ignore) {
                // 换下一种格式继续尝试
            }
        }
        return 0L;
    }

    /**
     * 解析 OAP 存储用的紧凑时间桶（无分隔符）
     * <p>
     * 依次尝试 14 / 12 / 10 / 8 位，分别对应 SECOND / MINUTE / HOUR / DAY。
     * 位数是唯一的判别依据，因此不需要外部传入步长。
     * </p>
     * <p>
     * <b>这里必须按「位数」逐个字段取，不能用「边界数组长度」做判断。</b>
     * 曾经用 {@code int[]{0,4,6,10}} 这样的边界数组，再拿数组长度去比 3/4/5，
     * 结果 12 位（MINUTE）的分钟位被判成「不存在」，整点之后的数据点全被压到整点，
     * 表现是：查 24 小时分钟级，只回来 25 个点（每小时一个），图上是一天 25 个台阶。
     * 这个错误用整点、整分的用例测不出来（秒/分恰好为 0 时结果一样），
     * 必须用「分钟/秒非 0」的时间做断言。
     * </p>
     *
     * @param value 纯数字时间桶
     * @return 毫秒时间戳；不是紧凑时间桶时返回 null
     */
    private static Long parseCompact(String value) {
        if (value.length() < 8 || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        // 位数 -> 各字段的结束下标：年 4 / 月 6 / 日 8 / 时 10 / 分 12 / 秒 14
        int end = value.length();
        if (end != 8 && end != 10 && end != 12 && end != 14) {
            return null;
        }
        try {
            int year = Integer.parseInt(value.substring(0, 4));
            int month = Integer.parseInt(value.substring(4, 6));
            int day = Integer.parseInt(value.substring(6, 8));
            int hour = end >= 10 ? Integer.parseInt(value.substring(8, 10)) : 0;
            int minute = end >= 12 ? Integer.parseInt(value.substring(10, 12)) : 0;
            int second = end >= 14 ? Integer.parseInt(value.substring(12, 14)) : 0;
            return java.time.LocalDateTime.of(year, month, day, hour, minute, second)
                    .atZone(zone())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 步长对应的毫秒数
     *
     * @param step 步长（已规范化或大小写混写均可）
     * @return 毫秒；无法识别返回 0
     */
    public static long stepMillis(String step) {
        if (step == null || step.isBlank()) {
            return 0L;
        }
        return switch (step.trim().toUpperCase(Locale.ROOT)) {
            case STEP_SECOND -> 1_000L;
            case STEP_MINUTE -> 60_000L;
            case STEP_HOUR -> 3_600_000L;
            case STEP_DAY -> DAY_MS;
            default -> 0L;
        };
    }

    /**
     * 构建 GraphQL {@code Duration} 变量
     *
     * @param start 起始毫秒
     * @param end   结束毫秒
     * @param step  步长
     * @return 变量 Map
     */
    public static Map<String, Object> duration(long start, long end, String step) {
        String normalized = normalizeStep(step, start, end);
        Map<String, Object> map = new LinkedHashMap<>(4);
        map.put("start", format(start, normalized));
        map.put("end", format(end, normalized));
        map.put("step", normalized);
        return map;
    }

    /**
     * 当前时区
     *
     * @return 时区
     */
    private static ZoneId zone() {
        try {
            return ZoneId.systemDefault();
        } catch (Throwable ignore) {
            return ZoneId.of("UTC");
        }
    }

    /**
     * 便捷方法：对每个毫秒时间戳做格式化
     *
     * @param step 步长
     * @return 格式化函数
     */
    public static Function<Long, String> formatterOf(String step) {
        return epoch -> format(epoch, step);
    }
}