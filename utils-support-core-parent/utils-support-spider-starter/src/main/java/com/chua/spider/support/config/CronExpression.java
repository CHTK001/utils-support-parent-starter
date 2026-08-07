package com.chua.spider.support.config;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Cron expression parser (simplified).
 *
 * <p>Supports: minute hour day month weekday (5 fields).</p>
 * <ul>
 *   <li>{@code *} - any value</li>
 *   <li>{@code N} - fixed value</li>
 *   <li>{@code step} ({@code 0/N} syntax) - step interval</li>
 *   <li>{@code a,b,c} - list</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CronExpression {

    /**
     * Field delimiter regex
     */
    private static final String FIELD_DELIMITER = "\\s+";

    /**
     * List delimiter
     */
    private static final String LIST_DELIMITER = ",";

    /**
     * Step prefix
     */
    private static final String STEP_PREFIX = "*/";

    /**
     * Cron expression text
     */
    private final String expression;

    /**
     * Minute field (0-59)
     */
    private final Field minutes;

    /**
     * Hour field (0-23)
     */
    private final Field hours;

    /**
     * Day-of-month field (1-31)
     */
    private final Field daysOfMonth;

    /**
     * Month field (1-12)
     */
    private final Field months;

    /**
     * Day-of-week field (1-7, Mon=1, Sun=7), nullable
     */
    private final Field daysOfWeek;

    /**
     * Parse cron expression.
     *
     * @param expression cron text
     */
    public CronExpression(String expression) {
        if (expression == null || expression.isEmpty()) {
            throw new IllegalArgumentException("cron expression must not be empty");
        }
        this.expression = expression.trim();
        String[] parts = this.expression.split(FIELD_DELIMITER);
        if (parts.length < 5 || parts.length > 6) {
            throw new IllegalArgumentException("cron expression must have 5-6 fields, got " + parts.length);
        }
        this.minutes = parseField(parts[0], 0, 59);
        this.hours = parseField(parts[1], 0, 23);
        this.daysOfMonth = parseField(parts[2], 1, 31);
        this.months = parseField(parts[3], 1, 12);
        this.daysOfWeek = parts.length == 6 ? parseField(parts[4], 1, 7) : null;
    }

    /**
     * Compute next trigger time after given base time.
     *
     * @param base base time (exclusive)
     * @return next trigger time, or null if not found within 1 year
     */
    public LocalDateTime nextAfter(LocalDateTime base) {
        ZonedDateTime cursor = base.atZone(ZoneId.systemDefault()).plusMinutes(1)
                .withSecond(0).withNano(0);
        for (int i = 0; i < 366 * 24 * 60; i++) {
            if (matches(cursor)) {
                return cursor.toLocalDateTime();
            }
            cursor = cursor.plusMinutes(1);
        }
        return null;
    }

    /**
     * Test if given time matches cron expression.
     *
     * @param zdt time to test
     * @return true if match
     */
    public boolean matches(ZonedDateTime zdt) {
        if (!months.contains(zdt.getMonthValue())) {
            return false;
        }
        if (daysOfWeek != null) {
            int dow = zdt.getDayOfWeek().getValue();
            if (!daysOfWeek.contains(dow)) {
                return false;
            }
            return minutes.contains(zdt.getMinute()) && hours.contains(zdt.getHour());
        }
        if (!daysOfMonth.contains(zdt.getDayOfMonth())) {
            return false;
        }
        return minutes.contains(zdt.getMinute()) && hours.contains(zdt.getHour());
    }

    /**
     * Parse a single cron field.
     *
     * @param expr field text
     * @param min  minimum value
     * @param max  maximum value
     * @return Field instance
     */
    private Field parseField(String expr, int min, int max) {
        if ("*".equals(expr)) {
            return Field.any(min, max);
        }
        if (expr.startsWith(STEP_PREFIX)) {
            int step = Integer.parseInt(expr.substring(STEP_PREFIX.length()));
            return Field.step(min, max, step);
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
     * Cron field.
     */
    private static final class Field {

        /**
         * Minimum value
         */
        private final int min;

        /**
         * Maximum value
         */
        private final int max;

        /**
         * Hit value bitset
         */
        private final boolean[] bits;

        private Field(int min, int max) {
            this.min = min;
            this.max = max;
            this.bits = new boolean[max - min + 1];
        }

        /**
         * Any value ({@code *})
         */
        static Field any(int min, int max) {
            Field f = new Field(min, max);
            for (int i = 0; i < f.bits.length; i++) {
                f.bits[i] = true;
            }
            return f;
        }

        /**
         * Step interval (e.g. 0/5 means every 5 units)
         */
        static Field step(int min, int max, int step) {
            Field f = new Field(min, max);
            for (int v = min; v <= max; v += step) {
                f.bits[v - min] = true;
            }
            return f;
        }

        /**
         * List ({@code a,b,c})
         */
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

        /**
         * Fixed value
         */
        static Field fixed(int min, int max, int value) {
            Field f = new Field(min, max);
            f.bits[value - min] = true;
            return f;
        }

        /**
         * Test if value is hit.
         *
         * @param value value to test
         * @return true if hit
         */
        boolean contains(int value) {
            if (value < min || value > max) {
                return false;
            }
            return bits[value - min];
        }
    }
}