package com.chua.spider.support.config;

import com.chua.common.support.utils.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * Cron 表达式解析器（Quartz 兼容）。
 *
 * <p>支持 5 字段或 6 字段（Quartz 格式，含秒）：
 * <pre>
 *   6 字段: seconds  minutes  hours  day-of-month  month  day-of-week
 *   5 字段:            minutes  hours  day-of-month  month  day-of-week
 * </pre>
 * 各字段支持：
 * <ul>
 *   <li>{@code *} - 任意值</li>
 *   <li>{@code ?} - 不指定（Quartz 用法，仅 DOM/DOW 有效）</li>
 *   <li>{@code N} - 固定值</li>
 *   <li>{@code a,b,c} - 列表</li>
 *   <li>{@code a/b} - 步进（{@code 0/5} 表示从 0 开始每 5 单位）</li>
 *   <li>{@code STAR/b} - 步进（从 min 开始每 b 单位，等价于 {@code min/b}）</li>
 * </ul>
 *
 * <p>DOM 与 DOW 遵循 Quartz OR 语义：
 * 两者都不是 {@code *} 时任一匹配即触发；
 * 其中一个是 {@code *} 时只看另一个。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CronExpression {

    /**
     * 字段分隔符正则。
     */
    private static final String FIELD_DELIMITER = "\\s+";

    /**
     * 列表分隔符。
     */
    private static final String LIST_DELIMITER = ",";

    /**
     * 是否使用 6 字段 Quartz 格式（含秒）。
     */
    private final boolean hasSeconds;

    /**
     * Cron 表达式原文。
     */
    private final String expression;

    private final Field seconds;
    private final Field minutes;
    private final Field hours;
    private final Field daysOfMonth;
    private final Field months;
    private final Field daysOfWeek;

    /**
     * DOM / DOW 是否为通配（影响 OR / AND 语义判定）。
     */
    private final boolean domIsAny;
    private final boolean dowIsAny;

    /**
     * 构造并解析 cron 表达式。
     *
     * @param expression cron 文本
     */
    public CronExpression(String expression) {
        if (StringUtils.isEmpty(expression)) {
            throw new IllegalArgumentException("cron expression must not be empty");
        }
        this.expression = expression.trim();
        String[] parts = this.expression.split(FIELD_DELIMITER);
        if (parts.length < 5 || parts.length > 6) {
            throw new IllegalArgumentException("cron expression must have 5-6 fields, got " + parts.length);
        }
        this.hasSeconds = parts.length == 6;
        if (hasSeconds) {
            this.seconds = parseField(parts[0], 0, 59);
            this.minutes = parseField(parts[1], 0, 59);
            this.hours = parseField(parts[2], 0, 23);
            this.daysOfMonth = parseField(parts[3], 1, 31);
            this.months = parseField(parts[4], 1, 12);
            this.daysOfWeek = parseField(parts[5], 1, 7);
            this.domIsAny = isWildcard(parts[3]);
            this.dowIsAny = isWildcard(parts[5]);
        } else {
            this.seconds = Field.any(0, 59);
            this.minutes = parseField(parts[0], 0, 59);
            this.hours = parseField(parts[1], 0, 23);
            this.daysOfMonth = parseField(parts[2], 1, 31);
            this.months = parseField(parts[3], 1, 12);
            this.daysOfWeek = parseField(parts[4], 1, 7);
            this.domIsAny = isWildcard(parts[2]);
            this.dowIsAny = isWildcard(parts[4]);
        }
    }

    /**
     * 判定字段是否为通配（{@code *} 或 {@code ?}）。
     */
    private static boolean isWildcard(String field) {
        if (StringUtils.isEmpty(field)) {
            return true;
        }
        return field.equals("*") || field.equals("?");
    }

    /**
     * 计算从基准时间之后的下一次触发时间。
     *
     * <p>6 字段 Quartz 模式按秒步进，5 字段模式按分钟步进。
     * 最坏情况扫描一年（5 字段 31.5 万分钟，6 字段扫描过大会更慢，调用方应控制）。</p>
     *
     * @param base 基准时间（不含本次）
     * @return 下次触发时间；找不到返回 null
     */
    public LocalDateTime nextAfter(LocalDateTime base) {
        ZonedDateTime cursor = base.atZone(ZoneId.systemDefault());
        if (hasSeconds) {
            cursor = cursor.plusSeconds(1).withNano(0);
        } else {
            cursor = cursor.plusMinutes(1).withSecond(0).withNano(0);
        }
        long maxIter = hasSeconds ? 366L * 24 * 60 * 60 : 366L * 24 * 60;
        ChronoUnit unit = hasSeconds ? ChronoUnit.SECONDS : ChronoUnit.MINUTES;
        for (long i = 0; i < maxIter; i++) {
            if (matches(cursor)) {
                return cursor.toLocalDateTime();
            }
            cursor = cursor.plus(1, unit);
        }
        return null;
    }

    /**
     * 判断给定时间是否匹配 cron 表达式。
     *
     * <p>DOM / DOW 遵循 Quartz OR 语义。</p>
     *
     * @param zdt 待测试时间
     * @return 是否匹配
     */
    public boolean matches(ZonedDateTime zdt) {
        if (!months.contains(zdt.getMonthValue())) {
            return false;
        }
        if (hasSeconds && !seconds.contains(zdt.getSecond())) {
            return false;
        }
        if (!minutes.contains(zdt.getMinute())) {
            return false;
        }
        if (!hours.contains(zdt.getHour())) {
            return false;
        }
        int dom = zdt.getDayOfMonth();
        int dow = zdt.getDayOfWeek().getValue();
        boolean domMatch = daysOfMonth.contains(dom);
        boolean dowMatch = daysOfWeek.contains(dow);
        boolean dayMatch;
        if (domIsAny && dowIsAny) {
            dayMatch = true;
        } else if (domIsAny) {
            dayMatch = dowMatch;
        } else if (dowIsAny) {
            dayMatch = domMatch;
        } else {
            dayMatch = domMatch || dowMatch;
        }
        return dayMatch;
    }

    /**
     * 解析单个 cron 字段。
     */
    private Field parseField(String expr, int min, int max) {
        if ("*".equals(expr) || "?".equals(expr)) {
            return Field.any(min, max);
        }
        int slash = expr.indexOf('/');
        if (slash >= 0) {
            int step = Integer.parseInt(expr.substring(slash + 1));
            String startPart = expr.substring(0, slash);
            int start;
            if ("*".equals(startPart) || startPart.isEmpty()) {
                start = min;
            } else {
                start = Integer.parseInt(startPart);
                if (start < min || start > max) {
                    throw new IllegalArgumentException(
                            "step start " + start + " out of range [" + min + "," + max + "]");
                }
            }
            return Field.step(start, max, step);
        }
        if (expr.contains(LIST_DELIMITER)) {
            String[] items = expr.split(LIST_DELIMITER);
            int[] values = new int[items.length];
            for (int i = 0; i < items.length; i++) {
                values[i] = Integer.parseInt(items[i]);
            }
            return Field.list(min, max, values);
        }
        int single = Integer.parseInt(expr);
        if (single < min || single > max) {
            throw new IllegalArgumentException("field value " + single + " out of range [" + min + "," + max + "]");
        }
        return Field.fixed(min, max, single);
    }

    @Override
    public String toString() {
        return expression;
    }

    /**
     * Cron 字段（位图表示）。
     */
    private static final class Field {

        private final int min;
        private final int max;
        private final boolean[] bits;

        private Field(int min, int max) {
            this.min = min;
            this.max = max;
            this.bits = new boolean[max - min + 1];
        }

        static Field any(int min, int max) {
            Field f = new Field(min, max);
            Arrays.fill(f.bits, true);
            return f;
        }

        static Field step(int start, int max, int step) {
            Field f = new Field(0, max);
            for (int v = start; v <= max; v += step) {
                f.bits[v] = true;
            }
            return f;
        }

        static Field list(int min, int max, int[] values) {
            Field f = new Field(min, max);
            for (int v : values) {
                if (v < min || v > max) {
                    throw new IllegalArgumentException("list value " + v + " out of range [" + min + "," + max + "]");
                }
                f.bits[v - min] = true;
            }
            return f;
        }

        static Field fixed(int min, int max, int value) {
            Field f = new Field(min, max);
            f.bits[value - min] = true;
            return f;
        }

        boolean contains(int value) {
            int idx = value - min;
            if (idx < 0 || idx >= bits.length) {
                return false;
            }
            return bits[idx];
        }
    }
}
