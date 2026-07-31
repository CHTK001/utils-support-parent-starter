package com.chua.common.support.task.scheduler;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.List;

/**
 * Cron 表达式解析引擎
 *
 * <p>负责解析标准的 6 字段 Cron 表达式（秒 分 时 日 月 周），并提供基于时间点的
 * 匹配和计算能力。该引擎通过将表达式编译为 {@link BitSet} 位图来实现高效的
 * 时间匹配判定。
 *
 * <p>支持的表达式语法：
 * <ul>
 *   <li>{@code *} — 所有值</li>
 *   <li>{@code ?} — 不指定值（仅用于日和周字段）</li>
 *   <li>{@code -} — 范围（如 1-5）</li>
 *   <li>{@code ,} — 列表（如 MON,WED,FRI）</li>
 *   <li>{@code /} - 步进（如 &#42;/5）</li>
 *   <li>{@code L} - 最后一天或最后一个工作日</li>
 *   <li>{@code W} - 最近的工作日</li>
 *   <li>{@code #} - 第 N 个星期几（如 3#2 表示第二个星期二）</li>
 * </ul>
 *
 * <p>匹配逻辑：
 * <ol>
 *   <li>按年-月-日-时-分-秒的层级逐级匹配</li>
 *   <li>日和周字段支持互斥匹配：当其中一个未设置时，另一个作为唯一判定条件</li>
 *   <li>支持月末（L）和最近工作日（W）等特殊语义</li>
 * </ol>
 *
 * @author CH
 * @since 1.0.0
 */
public class CronExpression {

    /**
     * 秒字段位图（0-59）
     */
    private final BitSet seconds = new BitSet(60);

    /**
     * 分钟字段位图（0-59）
     */
    private final BitSet minutes = new BitSet(60);

    /**
     * 小时字段位图（0-23）
     */
    private final BitSet hours = new BitSet(24);

    /**
     * 日期字段位图（1-31）
     */
    private final BitSet daysOfMonth = new BitSet(32);

    /**
     * 月份字段位图（1-12）
     */
    private final BitSet months = new BitSet(13);

    /**
     * 星期字段位图（0-7，0 和 7 均表示周日）
     */
    private final BitSet daysOfWeek = new BitSet(8);

    /**
     * 是否使用月末标记（L）
     */
    private boolean lastDayOfMonth;

    /**
     * 是否使用最后一个工作日标记（L，星期字段）
     */
    private boolean lastDayOfWeek;

    /**
     * 最近的工作日（W 修饰符）
     */
    private Integer nearestWeekday;

    /**
     * 第 N 个星期几（如 3#2 表示第二个星期二，# 前的数字表示星期几，# 后的数字表示第几个）
     */
    private Integer nthDayOfWeek;

    /**
     * 构造一个 Cron 表达式解析器
     *
     * <p>解析并编译给定的 6 字段 Cron 表达式。表达式格式为：
     * {@code 秒 分 时 日 月 周}，各字段之间用空格分隔。
     *
      * @param expression 6 字段 Cron 表达式
      * @throws IllegalArgumentException 如果表达式格式不正确或字段数不为 6
      */
    public CronExpression(String expression) {
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException("Cron expression must have 6 fields (second minute hour day-of-month month day-of-week)");
        }
        parseSeconds(fields[0]);
        parseMinutes(fields[1]);
        parseHours(fields[2]);
        parseDaysOfMonth(fields[3]);
        parseMonths(fields[4]);
        parseDaysOfWeek(fields[5]);
    }

    /**
     * 解析秒字段（0-59）
     */
    private void parseSeconds(String field) {
        parseField(field, 0, 59, seconds);
    }

    /**
     * 解析分钟字段（0-59）
     */
    private void parseMinutes(String field) {
        parseField(field, 0, 59, minutes);
    }

    /**
     * 解析小时字段（0-23）
     */
    private void parseHours(String field) {
        parseField(field, 0, 23, hours);
    }

    /**
     * 解析月份字段（1-12），支持 JAN-DEC 英文缩写
     */
    private void parseMonths(String field) {
        String resolved = resolveNames(field, "JAN,FEB,MAR,APR,MAY,JUN,JUL,AUG,SEP,OCT,NOV,DEC");
        parseField(resolved, 1, 12, months);
    }

    /**
     * 解析日期字段（1-31），支持 L、W 修饰符
     */
    private void parseDaysOfMonth(String field) {
        if ("?".equals(field)) {
            return;
        }
        if ("L".equals(field)) {
            lastDayOfMonth = true;
            return;
        }
        if (field.startsWith("LW")) {
            lastDayOfMonth = true;
            return;
        }
        if (field.endsWith("W") && field.length() > 1) {
            nearestWeekday = Integer.parseInt(field.substring(0, field.length() - 1));
            return;
        }
        parseField(field, 1, 31, daysOfMonth);
    }

    /**
     * 解析星期字段（0-7），支持 L、# 修饰符和 SUN-SAT 英文缩写
     */
    private void parseDaysOfWeek(String field) {
        if ("?".equals(field)) {
            return;
        }
        String resolved = resolveNames(field, "SUN,MON,TUE,WED,THU,FRI,SAT");
        resolved = resolved.replace("7", "0");
        if ("L".equals(resolved)) {
            lastDayOfWeek = true;
            return;
        }
        if (resolved.contains("#")) {
            String[] parts = resolved.split("#");
            nthDayOfWeek = Integer.parseInt(parts[0]);
            daysOfWeek.set(Integer.parseInt(parts[1]));
            return;
        }
        parseField(resolved, 0, 7, daysOfWeek);
        if (daysOfWeek.get(7)) {
            daysOfWeek.set(0);
        }
    }

    /**
     * 将英文名称解析为数字
     *
     * @param field 原始字段值
     * @param names 逗号分隔的名称列表
     * @return 将名称替换为数字后的字段值
     */
    private String resolveNames(String field, String names) {
        String[] nameArr = names.split(",");
        String result = field.toUpperCase();
        for (int i = 0; i < nameArr.length; i++) {
            result = result.replace(nameArr[i], String.valueOf(i + 1));
        }
        return result;
    }

    /**
     * 解析标准字段（支持 *、逗号列表）
     */
    private void parseField(String field, int min, int max, BitSet bits) {
        if ("*".equals(field)) {
            bits.set(min, max + 1);
            return;
        }
        for (String part : field.split(",")) {
            parseFieldPart(part, min, max, bits);
        }
    }

    /**
     * 解析字段中的单个部分（支持范围 - 和步进 /）
     */
    private void parseFieldPart(String part, int min, int max, BitSet bits) {
        int step = 1;
        int slash = part.indexOf('/');
        if (slash != -1) {
            step = Integer.parseInt(part.substring(slash + 1));
            part = part.substring(0, slash);
        }
        int rangeMin, rangeMax;
        int dash = part.indexOf('-');
        if ("*".equals(part)) {
            rangeMin = min;
            rangeMax = max;
        } else if (dash != -1) {
            rangeMin = Integer.parseInt(part.substring(0, dash));
            rangeMax = Integer.parseInt(part.substring(dash + 1));
        } else {
            rangeMin = Integer.parseInt(part);
            rangeMax = Integer.parseInt(part);
        }
        for (int i = rangeMin; i <= rangeMax; i += step) {
            bits.set(i);
        }
    }

    /**
     * 计算从指定时间之后的下一个匹配时间点
     *
     * <p>按照年-月-日-时-分-秒的层级逐级递增匹配：
     * <ol>
     *   <li>先匹配月份，不匹配则跳到下个月第一天</li>
     *   <li>再匹配日期和星期，日和周采用或逻辑（只要一个满足即可）</li>
     *   <li>然后匹配小时、分钟、秒</li>
     * </ol>
     *
     * @param from 基准时间
     * @return 下一个匹配的 Cron 时间点
     */
    LocalDateTime nextExecutionTime(LocalDateTime from) {
        LocalDateTime candidate = from.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);

        while (true) {
            int y = candidate.getYear();
            int m = candidate.getMonthValue();

            if (!months.get(m)) {
                candidate = candidate.withMonth(m + 1).withDayOfMonth(1).with(LocalTime.MIN);
                continue;
            }

            int dom = candidate.getDayOfMonth();
            int dow = candidate.getDayOfWeek().getValue() % 7;

            boolean domMatch = daysOfMonth.get(dom)
                    || (lastDayOfMonth && dom == lengthOfMonth(y, m));
            boolean dowMatch = daysOfWeek.get(dow)
                    || (lastDayOfWeek && isLastWeekDay(candidate));
            if (nthDayOfWeek != null && daysOfWeek.get(nthDayOfWeek)) {
                dowMatch = isNthDayOfWeek(candidate);
            }
            if (nearestWeekday != null) {
                domMatch = isNearestWeekday(y, m, candidate);
            }
            if ((daysOfMonth.isEmpty() && !lastDayOfMonth && nearestWeekday == null)
                    || (daysOfWeek.isEmpty() && !lastDayOfWeek && nthDayOfWeek == null)) {
                domMatch = true;
                dowMatch = true;
            }

            if (!domMatch || !dowMatch) {
                candidate = candidate.plusDays(1).with(LocalTime.MIN);
                continue;
            }

            int h = candidate.getHour();
            if (!hours.get(h)) {
                candidate = candidate.withHour(h + 1).withMinute(0).withSecond(0);
                continue;
            }

            int min = candidate.getMinute();
            if (!minutes.get(min)) {
                candidate = candidate.withMinute(min + 1).withSecond(0);
                continue;
            }

            int sec = candidate.getSecond();
            if (!seconds.get(sec)) {
                candidate = candidate.plusSeconds(1);
                continue;
            }

            return candidate;
        }
    }

    /**
     * 从指定时间开始获取 N 个执行时间点
     *
     * @param count 需要获取的数量
     * @param from  基准时间
     * @return 执行时间点列表
     */
    List<LocalDateTime> getFireTimes(int count, LocalDateTime from) {
        List<LocalDateTime> result = new ArrayList<>(count);
        LocalDateTime current = from;
        for (int i = 0; i < count; i++) {
            current = nextExecutionTime(current);
            if (current == null) {
                break;
            }
            result.add(current);
            current = current.plusSeconds(1);
        }
        return result;
    }

    /**
     * 获取指定月份的天数
     */
    private int lengthOfMonth(int year, int month) {
        return LocalDate.of(year, month, 1).lengthOfMonth();
    }

    /**
     * 判断是否为该月最后一个工作日（周五为最后一个工作日时，周六日往后顺延）
     */
    private boolean isLastWeekDay(LocalDateTime dt) {
        LocalDate date = dt.toLocalDate();
        LocalDate lastDay = date.with(TemporalAdjusters.lastDayOfMonth());
        return date.equals(lastDay) || date.getDayOfWeek() == lastDay.getDayOfWeek();
    }

    /**
     * 判断是否为第 N 个星期几
     */
    private boolean isNthDayOfWeek(LocalDateTime dt) {
        int weekOfMonth = (dt.getDayOfMonth() - 1) / 7 + 1;
        int dow = dt.getDayOfWeek().getValue() % 7;
        return daysOfWeek.get(dow) && dt.getDayOfWeek().getValue() == nthDayOfWeek;
    }

    /**
     * 获取从指定时间之后的下一个有效执行时间（兼容 Quartz 风格）。
     *
     * @param fromTime 基准时间
     * @return 下一个有效时间，如果无法确定则返回 {@code null}
     */
    public Date getNextValidTimeAfter(Date fromTime) {
        LocalDateTime fromLdt = fromTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        LocalDateTime next = nextExecutionTime(fromLdt);
        return next == null ? null : Date.from(next.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 判断是否为最近的工作日（W 修饰符）
     *
     * <p>如果指定日期是周六，则返回周五；如果是周日，则返回周一；
     * 如果调整后跨月，则向前取最近的工作日。
     */
    private boolean isNearestWeekday(int year, int month, LocalDateTime dt) {
        LocalDate target = LocalDate.of(year, month, nearestWeekday);
        LocalDate nearest = target;
        if (target.getDayOfWeek() == DayOfWeek.SATURDAY) {
            nearest = target.minusDays(1);
        } else if (target.getDayOfWeek() == DayOfWeek.SUNDAY) {
            nearest = target.plusDays(1);
        }
        if (nearest.getMonthValue() != month) {
            nearest = target.minusDays(1);
        }
        return dt.toLocalDate().equals(nearest);
    }
}
