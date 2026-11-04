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
     * 解析时间点字符串，失败返回 0
     *
     * @param text 时间字符串
     * @param step 步长
     * @return 毫秒时间戳
     */
    public static long parse(String text, String step) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        String value = text.trim();
        // OAP 可能返回 "yyyy-MM-dd HHmmss" 形式的带秒串，即便查询用分钟步长
        for (String candidateStep : new String[]{normalizeStep(step, 0, 0), STEP_SECOND, STEP_MINUTE, STEP_HOUR, STEP_DAY}) {
            try {
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
