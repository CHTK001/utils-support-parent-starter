package com.chua.common.support.lang.date;

import com.chua.common.support.lang.date.enums.ZoneIdEnum;
import com.chua.common.support.lang.date.unit.DateUnit;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
* 日期时间工具类。
* <p>提供静态方法封装 {@link LocalDateTime}、{@link ZonedDateTime}、{@link Date} 之间的转换、解析、格式化、计算、比较及时区处理。</p>
*
* @author CH
* @since 2026/07/19
 */
public final class DateTime {

    /**
    * 系统默认时区
    */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** 创建 DateTime 实例 */
    private DateTime() {
    }

    // ======================== 当前时间 ========================

    /**
    * 获取当前日期时间（系统默认时区）。
    *
    * @return 当前 {@link LocalDateTime}
    */
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
    * 获取当前日期时间（指定时区）。
    *
    * @param zone 时区
    * @return 当前 {@link LocalDateTime}
    */
    public static LocalDateTime now(ZoneId zone) {
        return LocalDateTime.now(zone);
    }

    /**
    * 获取当前时间戳（毫秒）。
    *
    * @return 当前毫秒时间戳
    */
    public static long nowMillis() {
        return System.currentTimeMillis();
    }

    // ======================== 解析 ========================

    /**
    * 字符串转 {@link LocalDateTime}。
    * <p>支持格式：yyyy-MM-dd HH:mm:ss、yyyy-MM-ddTHH:mm:ss 等。</p>
    *
    * @param timeStr 时间字符串
    * @return {@link LocalDateTime}
    */
    public static LocalDateTime parse(String timeStr) {
        return LocalDateTime.parse(timeStr);
    }

    /**
    * 字符串按指定格式转 {@link LocalDateTime}。
    *
    * @param timeStr 时间字符串
    * @param pattern 格式表达式
    * @return {@link LocalDateTime}
    */
    public static LocalDateTime parse(String timeStr, String pattern) {
        return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern(pattern));
    }

    /**
    * 毫秒时间戳转 {@link LocalDateTime}。
    *
    * @param epochMilli 毫秒时间戳
    * @return {@link LocalDateTime}
    */
    public static LocalDateTime parse(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZONE);
    }

    /**
    * {@link Date} 转 {@link LocalDateTime}。
    * <p>date 为空时返回当前时间。</p>
    *
    * @param date 日期
    * @return {@link LocalDateTime}
    */
    public static LocalDateTime parse(Date date) {
        if (date == null) {
            date = new Date();
        }
        return parse(date.getTime());
    }

    // ======================== 格式化 ========================

    /**
    * {@link LocalDateTime} 转字符串（默认格式 yyyy-MM-dd HH:mm:ss）。
    *
    * @param dateTime 日期时间
    * @return 格式化字符串
    */
    public static String format(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
    * {@link LocalDateTime} 按指定格式转字符串。
    *
    * @param dateTime 日期时间
    * @param pattern  格式表达式
    * @return 格式化字符串
    */
    public static String format(LocalDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
    * {@link LocalDateTime} 转毫秒时间戳。
    *
    * @param dateTime 日期时间
    * @return 毫秒时间戳
    */
    public static long toMillis(LocalDateTime dateTime) {
        if (dateTime == null) {
            return 0L;
        }
        return dateTime.atZone(ZONE).toInstant().toEpochMilli();
    }

    /**
    * {@link LocalDateTime} 转 {@link Date}。
    *
    * @param dateTime 日期时间
    * @return {@link Date}
    */
    public static Date toDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return Date.from(dateTime.atZone(ZONE).toInstant());
    }

    // ======================== 时间计算 ========================

    /**
    * 当前时间加指定年数。
    *
    * @param years 年数（可为负数）
    * @return 新的 {@link LocalDateTime}
    */
    public static LocalDateTime plusYears(long years) {
        return now().plusYears(years);
    }

    /**
    * 当前时间加指定月数。
    *
    * @param months 月数（可为负数）
    * @return 新的 {@link LocalDateTime}
    */
    public static LocalDateTime plusMonths(long months) {
        return now().plusMonths(months);
    }

    /**
    * 当前时间加指定天数。
    *
    * @param days 天数（可为负数）
    * @return 新的 {@link LocalDateTime}
    */
    public static LocalDateTime plusDays(long days) {
        return now().plusDays(days);
    }

    /**
    * 当前时间加指定小时数。
    *
    * @param hours 小时数（可为负数）
    * @return 新的 {@link LocalDateTime}
    */
    public static LocalDateTime plusHours(long hours) {
        return now().plusHours(hours);
    }

    /**
    * 当前时间加指定分钟数。
    *
    * @param minutes 分钟数（可为负数）
    * @return 新的 {@link LocalDateTime}
    */
    public static LocalDateTime plusMinutes(long minutes) {
        return now().plusMinutes(minutes);
    }

    /**
    * 当前时间加指定秒数。
    *
    * @param seconds 秒数（可为负数）
    * @return 新的 {@link LocalDateTime}
    */
    public static LocalDateTime plusSeconds(long seconds) {
        return now().plusSeconds(seconds);
    }

    /**
    * 给定时间加指定单位时间。
    *
    * @param dateTime 原始时间
    * @param amount   数量（可为负数）
    * @param unit     时间单位
    * @return 新的 {@link LocalDateTime}
    */
    public static LocalDateTime plus(LocalDateTime dateTime, long amount, DateUnit unit) {
        if (dateTime == null || unit == null) {
            return dateTime;
        }
        return switch (unit) {
            case DateUnit.YEAR -> dateTime.plusYears(amount);
            case DateUnit.MONTH -> dateTime.plusMonths(amount);
            case DateUnit.DAY -> dateTime.plusDays(amount);
            case DateUnit.WEEK -> dateTime.plusWeeks(amount);
            case DateUnit.HOUR -> dateTime.plusHours(amount);
            case DateUnit.MINUTE -> dateTime.plusMinutes(amount);
            case DateUnit.SECOND -> dateTime.plusSeconds(amount);
            case DateUnit.MS -> dateTime.plusNanos(amount * 1000);
            case DateUnit.CUSTOM -> dateTime.plusDays(amount);
        };
    }

    // ======================== 时间差 ========================

    /**
    * 计算两个时间之间的天数差。
    *
    * @param start 开始时间
    * @param end   结束时间
    * @return 天数差
    */
    public static long betweenDays(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0L;
        }
        return Duration.between(start, end).toDays();
    }

    /**
    * 计算两个时间之间的小时差。
    *
    * @param start 开始时间
    * @param end   结束时间
    * @return 小时差
    */
    public static long betweenHours(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0L;
        }
        return Duration.between(start, end).toHours();
    }

    /**
    * 计算两个时间之间的分钟差。
    *
    * @param start 开始时间
    * @param end   结束时间
    * @return 分钟差
    */
    public static long betweenMinutes(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0L;
        }
        return Duration.between(start, end).toMinutes();
    }

    /**
    * 计算两个时间之间的秒差。
    *
    * @param start 开始时间
    * @param end   结束时间
    * @return 秒差
    */
    public static long betweenSeconds(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0L;
        }
        return Duration.between(start, end).toSeconds();
    }

    /**
    * 计算两个时间之间的毫秒差。
    *
    * @param start 开始时间
    * @param end   结束时间
    * @return 毫秒差
    */
    public static long betweenMillis(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0L;
        }
        return Duration.between(start, end).toMillis();
    }

    // ======================== 时间比较 ========================

    /**
    * 判断是否在两个时间之间（包含边界）。
    *
    * @param time 待判断时间
    * @param start 开始时间
    * @param end   结束时间
    * @return true 在区间内
    */
    public static boolean isBetween(LocalDateTime time, LocalDateTime start, LocalDateTime end) {
        if (time == null || start == null || end == null) {
            return false;
        }
        return !time.isBefore(start) && !time.isAfter(end);
    }

    /**
    * 判断给定时间是否在今天。
    *
    * @param time 待判断时间
    * @return true 是今天
    */
    public static boolean isToday(LocalDateTime time) {
        if (time == null) {
            return false;
        }
        LocalDate today = LocalDate.now();
        LocalDate target = time.toLocalDate();
        return today.equals(target);
    }

    // ======================== 时间区间 ========================

    /**
    * 创建时间区间。
    *
    * @param start 开始时间
    * @param end   结束时间
    * @return {@link DateTimeRange}
    */
    public static DateTimeRange range(LocalDateTime start, LocalDateTime end) {
        return new DateTimeRange(start, end);
    }

    /**
    * 创建时间区间（起始偏移量）。
    *
    * @param start   开始时间
    * @param endDays 结束时间偏移天数
    * @return {@link DateTimeRange}
    */
    public static DateTimeRange range(LocalDateTime start, long endDays) {
        return new DateTimeRange(start, start.plusDays(endDays));
    }

    // ======================== 时区处理 ========================

    /**
    * 带时区转换。
    *
    * @param dateTime 日期时间
    * @param zone     目标时区
    * @return 目标时区的 {@link ZonedDateTime}
    */
    public static ZonedDateTime withZone(LocalDateTime dateTime, ZoneId zone) {
        if (dateTime == null || zone == null) {
            return null;
        }
        return dateTime.atZone(zone);
    }

    /**
    * 将 {@link LocalDateTime} 转为指定时区的毫秒时间戳。
    *
    * @param dateTime 日期时间
    * @param zone     目标时区
    * @return 毫秒时间戳
    */
    public static long toEpochMilli(LocalDateTime dateTime, ZoneId zone) {
        if (dateTime == null || zone == null) {
            return 0L;
        }
        return dateTime.atZone(zone).toInstant().toEpochMilli();
    }

    /**
    * 将 {@link LocalDateTime} 转为指定时区的时间字符串。
    *
    * @param dateTime 日期时间
    * @param zone     目标时区
    * @param pattern  格式表达式
    * @return 格式化字符串
    */
    public static String format(LocalDateTime dateTime, ZoneId zone, String pattern) {
        if (dateTime == null || zone == null || pattern == null) {
            return "";
        }
        return dateTime.atZone(zone).format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
    * 从时区枚举创建带时区的日期时间。
    *
    * @param dateTime 日期时间
    * @param zoneEnum 时区枚举
    * @return 带时区的 {@link ZonedDateTime}
    */
    public static ZonedDateTime ofZone(LocalDateTime dateTime, ZoneIdEnum zoneEnum) {
        if (dateTime == null || zoneEnum == null) {
            return null;
        }
        return dateTime.atZone(ZoneId.of(zoneEnum.getZoneIdName()));
    }
}
