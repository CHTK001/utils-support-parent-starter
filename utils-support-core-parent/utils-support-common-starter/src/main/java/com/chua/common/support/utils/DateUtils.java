package com.chua.common.support.utils;
import com.chua.common.support.constant.DateFormatConstant;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.date.unit.DateUnit;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.chua.common.support.constant.NumberConstant.NUMBER_10;
import static java.util.Calendar.DAY_OF_WEEK;
import static java.util.Calendar.getInstance;
/**
 * 日期时间工具类，提供日期时间解析、格式化、计算、比较等操作。
 *
 * <p>支持的日期时间类型：
 * <ul>
 *   <li>Date / java.sql.Date / java.sql.Time / java.sql.Timestamp</li>
 *   <li>LocalDate / LocalTime / LocalDateTime / ZonedDateTime / Instant</li>
 *   <li>YearMonth / Year / MonthDay</li>
 *   <li>long 时间戳 / Calendar / String</li>
 * </ul>
 *
 * <p>功能包括日期解析、格式化、时间单位转换、日期计算（加减天数、月份、年份）、
 * 日期比较、时区处理、工作日计算等。
 * 部分方法参考 Apache Commons Lang 实现。
 *
 * @author CH
 * @版本 4.0.0.42
 * @since 2020/12/21
 */
@Slf4j
public class DateUtils {
    /**
     * 日期工具。
     */
    private DateUtils() {
    }
    /**
     * T
     */
    public static final String SYMBOL_VIRTUAL_T = "T";
    /**
     *             (double)
     */
    public static final String SYMBOL_VIRTUAL_D = "D";
    /**
     * 3
     */
    public static final int THIRD = 3;
    /** One_day */
    public static final int ONE_DAY = 24 * 60 * 60;
    /** One_hour */
    public static final int ONE_HOUR = 60;
    /** One_minute */
    public static final int ONE_MINUTE = ONE_HOUR;
    /** 准确_hours */
    public static final int ACCURACY_HOURS = 4;
    /** 准确_minutes */
    public static final int ACCURACY_MINUTES = 5;
    /** 准确_seconds */
    public static final int ACCURACY_SECONDS = 6;
    /** 准确_milliseconds */
    public static final int ACCURACY_MILLISECONDS = 7;
    /** 准确_milliseconds_forced */
    public static final int ACCURACY_MILLISECONDS_FORCED = 8;
    /**
     * Milliseconds per seconde
     */
    public static final int MILLISECONDS_PER_SECONDE = 1000;
    /**
     *                 60*1000
     */
    public static final int MILLISECONDS_PER_MINUTE = 60000;
    /**
     *                    36*60*1000
     */
    public static final int MILLISECONDS_PER_HOUR = 3600000;
    /**
     *                 24*60*60*1000;
     */
    public static final long MILLISECONDS_PER_DAY = 86400000;
    /** Week */
    public static final String WEEK = "week";
    /** Week_day */
    public static final int WEEK_DAY = 7;
    /** 默认_zone_标识 */
    public static final ZoneId DEFAULT_ZONE_ID = ZoneId.systemDefault();
    /**
     * Hours per day.
     */
    static final int HOURS_PER_DAY = 24;
    /**
     * Minutes per hour.
     */
    static final int MINUTES_PER_HOUR = 60;
    /**
     * Minutes per day.
     */
    static final int MINUTES_PER_DAY = MINUTES_PER_HOUR * HOURS_PER_DAY;
    /**
     * Seconds per minute.
     */
    static final int SECONDS_PER_MINUTE = 60;
    /**
     * Seconds per hour.
     */
    static final int SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    /**
     * Seconds per day.
     */
    static final int SECONDS_PER_DAY = SECONDS_PER_HOUR * HOURS_PER_DAY;
    /**
     * Milliseconds per day.
     */
    static final long MILLIS_PER_DAY = SECONDS_PER_DAY * 1000L;
    /**
     * Microseconds per day.
     */
    static final long MICROS_PER_DAY = SECONDS_PER_DAY * 1000_000L;
    /**
     * nano per second.
     */
    static final long NANOS_PER_SECOND = 1000_000_000L;
    /**
     * nano per minute.
     */
    static final long NANOS_PER_MINUTE = NANOS_PER_SECOND * SECONDS_PER_MINUTE;
    /**
     * nano per hour.
     */
    static final long NANOS_PER_HOUR = NANOS_PER_MINUTE * MINUTES_PER_HOUR;
    /**
     * nano per day.
     */
    static final long NANOS_PER_DAY = NANOS_PER_HOUR * HOURS_PER_DAY;
    /**
     * unix
     */
    private static final int UNIX_LENGTH = 10;
    /**
     * Millisecond 常量
     */
    private static final int MILLISECOND = 13;
    /**
     * =====================================            ===============================================
     */
    private static final String YEAR = "year";
    /** Month */
    private static final String MONTH = "month";
    /** DAY */
    private static final String DAY = "day";
    /** Hour */
    private static final String HOUR = "hour";
    /** Minute */
    private static final String MINUTE = "minute";
    /** Second */
    private static final String SECOND = "second";
    /**
     * Monday 常量
     */
    private static final String MONDAY = "MONDAY";
    /**
     * Tuesday 常量
     */
    private static final String TUESDAY = "TUESDAY";
    /**
     * Wednesday 常量
     */
    private static final String WEDNESDAY = "WEDNESDAY";
    /**
     * Thursday 常量
     */
    private static final String THURSDAY = "THURSDAY";
    /**
     * Friday 常量
     */
    private static final String FRIDAY = "FRIDAY";
    /**
     * Saturday 常量
     */
    private static final String SATURDAY = "SATURDAY";
    /**
     * Sunday 常量
     */
    private static final String SUNDAY = "SUNDAY";
    /** 模式 */
    private static final Pattern PATTERN =
            Pattern.compile("([-+]?)P(?:([-+]?[0-9]+)D)?" +
                            "(T(?:([-+]?[0-9]+)H)?(?:([-+]?[0-9]+)M)?(?:([-+]?[0-9]+)(?:[.,]([0-9]{0,9}))?S)?)?",
                    Pattern.CASE_INSENSITIVE);
    /** 日期_格式化 */
    private final static String[] DATE_FORMATS = {
            "yyyy-MM-dd'T'HH:mm:ss.SSS+08:00",
            "E M d H:m:s z yyyy",
            "EEE, d MMM yyyy HH:mm:ss z",
            "EEE MMM dd HH:mm:ss z yyyy",
            "EEE MMM dd HH:mm:ss zzz yyyy",
            "EEE MMM dd HH:mm:ss yyyy",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd HH:mm:ss.SSSZ",
            "yyyy-MM-dd HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy   MM   dd    HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyyMMddHHmmss",
            "HH:mm:ss",
            "HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy   MM   dd    HH:mm",
            "yyyy-MM-dd HH:mm",
            "yyyyMMddHHmm",
            "yyyy/MM/dd HH:mm",
            "yyyy   MM   dd    HH",
            "yyyy-MM-dd HH",
            "yyyyMMddHH",
            "yyyy/MM/dd HH",
            "yyyy   MM   dd   ",
            "yyyy-MM-dd",
            "yyyyMMdd",
            "yyyy/MM/dd",
            "yyyy   MM   ",
            "yyyy-MM",
            "yyyy/MM",
            "yyyy   "
    };
    /** 索引_not_found */
    private static final int INDEX_NOT_FOUND = -1;
    /**
     * 将 ISO-8601 时长字符串（如 "PT5H30M"）解析为 {@link Duration}。
     *
     * @param time ISO-8601 时长格式字符串，不含前缀
     * @return 解析后的 持续时间
     * @throws java.time.format.DateTimeParseException 格式不正确时抛出
     */
    public static Duration toDuration(String time) {
        return Duration.parse("PT" + time);
    }
    /**
     * 将 ISO-8601 周期字符串（如 "P30D"）解析为 {@link Period}。
     *
     * @param time ISO-8601 周期格式字符串，不含前缀
     * @return 解析后的 周期
     * @throws java.time.format.DateTimeParseException 格式不正确时抛出
     */
    public static Period toPeriod(String time) {
        return Period.parse("P" + time);
    }
    /**
     * 将两个 日期 之间的时间差（绝对值）按指定单位换算。
     *
     * @param date      第一个日期
     * @param date1     第二个日期
     * @param dateUnit  时间单位，决定返回值的时间粒度
     * @return 两日期之间的时间单位数（绝对值）
     */
    public static long between(Date date, Date date1, DateUnit dateUnit) {
        return Math.abs(date.getTime() - date1.getTime()) / dateUnit.getMillis();
    }
    /**
     * 判断当前是否处于白天时段（6:00 ~ 18:00）。
     *
     * @return 当前小时在 6~18 之间返回 true
     */
    public static boolean isDay() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return hour >= 6 && hour < 18;
    }
    /**
     * 判断 日期1 是否在 日期2 之后。
     *
     * @param date1 待比较日期
     * @param date2 比较基准日期
     * @return date1 在 日期2 之后返回 true；任一参数为 空 返回 false
     */
    public Boolean after(Date date1, Date date2) {
        if (null == date1 || null == date2) {
            return false;
        }
        LocalDateTime localDateTime1 = LocalDateTime.ofInstant(date1.toInstant(), DEFAULT_ZONE_ID);
        LocalDateTime localDateTime2 = LocalDateTime.ofInstant(date2.toInstant(), DEFAULT_ZONE_ID);
        return localDateTime1.isAfter(localDateTime2);
    }
    /**
     * 判断 日期1 是否在 日期2 之前。
     *
     * @param date1 待比较日期
     * @param date2 比较基准日期
     * @return date1 在 日期2 之前返回 true；任一参数为 空 返回 false
     */
    public Boolean before(Date date1, Date date2) {
        if (null == date1 || null == date2) {
            return false;
        }
        LocalDateTime localDateTime1 = LocalDateTime.ofInstant(date1.toInstant(), DEFAULT_ZONE_ID);
        LocalDateTime localDateTime2 = LocalDateTime.ofInstant(date2.toInstant(), DEFAULT_ZONE_ID);
        return localDateTime1.isBefore(localDateTime2);
    }
    /**
     * 判断两个 日期 是否相等。
     *
     * @param date1 待比较日期
     * @param date2 比较基准日期
     * @return 两日期相等返回 true；任一参数为 空 返回 false
     */
    public Boolean equal(Date date1, Date date2) {
        if (null == date1 || null == date2) {
            return false;
        }
        LocalDateTime localDateTime1 = LocalDateTime.ofInstant(date1.toInstant(), DEFAULT_ZONE_ID);
        LocalDateTime localDateTime2 = LocalDateTime.ofInstant(date2.toInstant(), DEFAULT_ZONE_ID);
        return localDateTime1.isEqual(localDateTime2);
    }
    /**
     * 获取指定日期之前或之后的日期。
     *
     * @param date 基准日期，为 空 时使用当前时间
     * @param beforeOrAfter 偏移天数，负数表示之前，正数表示之后
     * @return 偏移后的日期
     */
    public Date getDayOfBeforeOrAfter(Date date, int beforeOrAfter) {
        if (null == date) {
            date = new Date();
        }
        LocalDateTime localDateTime = LocalDateTime.ofInstant(date.toInstant(), DEFAULT_ZONE_ID);
        localDateTime.plusDays(beforeOrAfter);
        return toDate(localDateTime);
    }
    /**
     * {日期}
     *
     * @param date the 日期
     * @return the 结果
     */
    public Date getDayOfYearday(Date date) {
        return getDayOfBeforeOrAfter(date, -1);
    }
    /**
     * 获取指定日期对应月份的第一天。
     *
     * @param date 基准日期，为 空 时使用当前时间
     * @return 该月第一天的 日期（时间设为 00:00:00）
     */
    public Date getFirstDayOfMonth(Date date) {
        if (null == date) {
            date = new Date();
        }
        LocalDateTime localDateTime = LocalDateTime.ofInstant(date.toInstant(), DEFAULT_ZONE_ID);
        //                     0   0   0
        localDateTime = localDateTime.with(TemporalAdjusters.firstDayOfMonth())
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        return toDate(localDateTime, DEFAULT_ZONE_ID);
    }
    /**
     * 获取指定日期对应星期的第一天（周一）。
     *
     * @param date 基准日期，为 空 时使用当前时间
     * @return 该周第一天的 日期（时间设为 00:00:00）
     */
    public Date getFirstDayOfWeek(Date date) {
        if (null == date) {
            date = new Date();
        }
        LocalDateTime localDateTime = LocalDateTime.ofInstant(date.toInstant(), DEFAULT_ZONE_ID);
        localDateTime.with(DayOfWeek.MONDAY)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .with(ChronoField.MILLI_OF_SECOND, 0)
                .withNano(0);
        return DateUtils.toDate(localDateTime);
    }
    /**
     * 获取指定日期当天的起始时刻（00:00:00.000）。
     *
     * @param date 基准日期，为 空 时返回 空
     * @return 当天起始时刻的 日期
     */
    public Date getFirstTimeOfDay(Date date) {
        if (null == date) {
            return null;
        }
        Instant instant = date.toInstant();
        LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, DEFAULT_ZONE_ID);
        localDateTime = localDateTime
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .with(ChronoField.MILLI_OF_SECOND, 0)
                .withNano(0);
        return toDate(localDateTime);
    }
    /**
     * 获取指定日期对应月份的最后一天的结束时刻（23:59:59）。
     *
     * @param date 基准日期，为 空 时使用当前时间
     * @return 该月最后一天的 日期（时间设为 23:59:59）
     */
    public Date getLastDayOfMonth(Date date) {
        if (null == date) {
            date = new Date();
        }
        LocalDateTime localDateTime = LocalDateTime.ofInstant(date.toInstant(), DEFAULT_ZONE_ID);
        //                           23   59   59
        localDateTime = localDateTime.with(TemporalAdjusters.lastDayOfMonth())
                .withHour(23)
                .withMinute(59)
                .withSecond(59);
        return toDate(localDateTime);
    }
    /**
     * 获取指定日期对应星期的最后一天（周日）的结束时刻（23:59:59.999）。
     *
     * @param date 基准日期，为 空 时使用当前时间
     * @return 该周最后一天的 日期
     */
    public Date getLastDayOfWeek(Date date) {
        if (null == date) {
            date = new Date();
        }
        LocalDateTime localDateTime = LocalDateTime.ofInstant(date.toInstant(), DEFAULT_ZONE_ID);
        localDateTime.with(DayOfWeek.SUNDAY)
                .withHour(23)
                .withMinute(59)
                .withSecond(59)
                .with(ChronoField.MILLI_OF_SECOND, 999);
        return DateUtils.toDate(localDateTime);
    }
    /**
     * 获取指定日期当天的结束时刻（23:59:59.999）。
     *
     * @param date 基准日期，为 空 时返回 空
     * @return 当天结束时刻的 日期
     */
    public Date getLastTimeOfDay(Date date) {
        if (null == date) {
            return null;
        }
        Instant instant = date.toInstant();
        LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, DEFAULT_ZONE_ID);
        localDateTime = localDateTime
                .withHour(23)
                .withMinute(59)
                .withSecond(59)
                .with(ChronoField.MILLI_OF_SECOND, 999);
        return toDate(localDateTime);
    }
    /**
     * 获取当前星期（0=周日，1=周一，...，6=周六）。
     *
     * @return 当前星期的整数表示
     */
    public int getWeek() {
        Date today = new Date();
        Calendar c = getInstance();
        c.setTime(today);
        return c.get(DAY_OF_WEEK) - 1;
    }
    /**
     * 获取指定日期的星期（0=周日，1=周一，...，6=周六）。
     *
     * @param date 基准日期
     * @return 星期的整数表示
     */
    public int getWeek(Date date) {
        Calendar c = getInstance();
        c.setTime(date);
        return c.get(DAY_OF_WEEK) - 1;
    }
    /**
     * 获取两个日期之间的所有日期（含起止），按时间升序排列。
     *
     * @param before 起始日期
     * @param after  结束日期
     * @return 包含起止日期的日期列表
     */
    public static List<Date> asRange(Date before, Date after) {
        List<Date> result = new ArrayList<>();
        LocalDate beforeLocalDate = toLocalDate(before);
        LocalDate afterLocalDate = toLocalDate(after);
        long beforeEpochMilli = beforeLocalDate.atStartOfDay(DEFAULT_ZONE_ID).toInstant().toEpochMilli();
        if (beforeEpochMilli == before.getTime()) {
            result.add(before);
            beforeLocalDate = beforeLocalDate.plusDays(1);
        }
        result.add(toDate(afterLocalDate));
        while (beforeLocalDate.isBefore(afterLocalDate)) {
            result.add(toDate(beforeLocalDate));
            beforeLocalDate = beforeLocalDate.plusDays(1);
        }
        result.sort((o1, o2) -> o1.after(o2) ? 1 : -1);
        return result;
    }
    /**
     * 获取当前时间的毫秒时间戳。
     *
     * @return 当前毫秒时间戳
     */
    public static long current() {
        return System.currentTimeMillis();
    }
    /**
     * 获取当前时间格式化字符串（格式：yyyy-MM-dd HH:mm:ss）。
     *
     * @return 当前时间字符串
     */
    public static String currentString() {
        return format(current(), DateFormatConstant.YYYY_MM_DD_HH_MM_SS);
    }
    /**
     * 获取当前日期字符串（格式：yyyy-MM-dd）。
     *
     * @return 当前日期字符串
     */
    public static String currentDateString() {
        return format(current(), DateFormatConstant.YYYY_MM_DD);
    }
    /**
     * 将字符串按指定格式解析为 日期。
     *
     * @param str     日期时间字符串
     * @param pattern 日期格式，如 "yyyy-MM-dd HH:mm:ss"
     * @return 解析后的 日期
     * @throws ParseException 解析失败时抛出
     */
    public static Date format(String str, String pattern) throws ParseException {
        DateFormat df = new SimpleDateFormat(pattern);
        return df.parse(str);
    }
    /**
     * 将 zoned日期时间 按指定格式字符串转换为日期字符串。
     *
     * @param zonedDateTime 带时区的时间对象
     * @param pattern       日期格式字符串，如 "yyyy-MM-dd HH:mm:ss"
     * @return 格式化后的字符串
     */
    public static String format(ZonedDateTime zonedDateTime, String pattern) throws ParseException {
        return format(zonedDateTime, DateTimeFormatter.ofPattern(pattern));
    }
    /**
     * 将 zoned日期时间 按指定 {@link DateTimeFormatter} 转换为日期字符串。
     *
     * @param zonedDateTime 带时区的时间对象
     * @param df            日期时间格式化器
     * @return 格式化后的字符串
     */
    public static String format(ZonedDateTime zonedDateTime, DateTimeFormatter df) throws ParseException {
        return zonedDateTime.format(df);
    }
    /**
     * 将 temporalaccessor 转为格式化字符串，根据类型自动选用默认格式。
     *
     * @param date 本地日期时间/本地日期/本地时间，其他类型返回 空
     * @return 格式化后的字符串
     */
    public static String format(TemporalAccessor date) {
        if (date instanceof LocalDateTime) {
            return format((LocalDateTime) date, DateFormatConstant.YYYY_MM_DD_HH_MM_SS);
        }
        if (date instanceof LocalDate) {
            return format((LocalDate) date, DateFormatConstant.YYYY_MM_DD);
        }
        if (date instanceof LocalTime) {
            return format((LocalTime) date, DateFormatConstant.HH_MM_SS);
        }
        return null;
    }
    /**
     * <br />                 yyyy-MM-dd HH:mm:ss
     * {@link #format(Date, String)}
     * <pre>
     *   DateHelper.format(new Date()) =           2020-05-23 17:06:30
     * </pre>
     *
     * @param date the 日期
     * @return the 结果
     * @see #format(Date, String)
     */
    public static String format(Date date) {
        return format(date, DateFormatConstant.YYYY_MM_DD_HH_MM_SS);
    }
    /**
     * <p>
     * 日期助手.格式化(新 日期(), "yyyy-MM-dd") =
     * </p>
     *
     * @param localDateTime the 本地日期时间
     * @param pattern the 模式
     * @return the 结果
     */
    public static String format(LocalDateTime localDateTime, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).format(localDateTime);
    }
    /**
     * <p>
     * 日期助手.格式化(新 日期(), "yyyy-MM-dd") =
     * </p>
     *
     * @param localTime the 本地时间
     * @param pattern the 模式
     * @return the 结果
     */
    public static String format(LocalTime localTime, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).format(localTime);
    }
    /**
     * <p>
     * 日期助手.格式化(新 日期(), "yyyy-MM-dd") =
     * </p>
     *
     * @param localDate the 本地日期
     * @param pattern the 模式
     * @return the 结果
     */
    public static String format(LocalDate localDate, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).format(localDate);
    }
    /**
     * <p>
     * 日期助手.格式化(新 日期(), "yyyy-MM-dd") =
     * </p>
     *
     * @param date the 日期
     * @param pattern the 模式
     * @return the 结果
     */
    public static String format(Date date, String pattern) {
        if (null == date || null == pattern) {
            return null;
        }
        DateFormat df = createDefaultDateFormat(pattern);
        return df.format(date);
    }
    /**
     *
     *
     * @param date the 日期
     * @param dateFormat the 日期格式化
     * @return the 结果
     */
    public static String format(Date date, DateFormat dateFormat) {
        if (null == date) {
            return null;
        }
        DateFormat df = null == dateFormat ? createDefaultDateFormat(DateFormatConstant.YYYY_MM_DD_HH_MM_SS) : dateFormat;
        return df.format(date);
    }
    /**
     * 将毫秒时间戳格式化为默认格式的日期字符串（yyyy-MM-dd HH:mm:ss）。
     *
     * @param time 毫秒时间戳
     * @return 格式化后的日期字符串
     */
    public static String format(long time) {
        return format(time, DateFormatConstant.YYYY_MM_DD_HH_MM_SS);
    }
    /**
     * 将毫秒时间戳按指定格式转换为日期字符串。
     *
     * @param time    毫秒时间戳
     * @param pattern 日期格式，如 "yyyy-MM-dd"
     * @return 格式化后的字符串
     */
    public static String format(long time, String pattern) {
        DateFormat df = createDefaultDateFormat(pattern);
        return df.format(time);
    }
    /**
     * 将毫秒时间戳按给定 {@link DateFormat} 转换为日期字符串。
     *
     * @param time         毫秒时间戳
     * @param dateFormat   日期格式对象，为 空 时使用默认格式
     * @return 格式化后的字符串
     */
    public static String format(long time, DateFormat dateFormat) {
        DateFormat df = null == dateFormat ? createDefaultDateFormat(DateFormatConstant.YYYY_MM_DD_HH_MM_SS) : dateFormat;
        return df.format(time);
    }
    /**
     * 获取指定节点（年/月/日/时/分/秒/周）相对当前时间的偏移结果。
     *
     * <p>num 为正数表示未来，为负数表示过去。</p>
     *
     * @param node  时间节点：{@code year/month/week/day/hour/minute/second}
     * @param num   偏移量（单位由 节点 决定）
     * @return 格式化后的时间字符串
     */
    public static String getAfterOrPreNowTime(final String node, final Long num) {
        return getAfterOrPreNowTime(node, num, DateFormatConstant.YYYY_MM_DD_HH_MM_SS_FMT);
    }
    /**
     *                                                    <br>
     * <ul>
     *                         2019-03-30 10:20:30
     * </ul>
     * <li>node="hour",num=5L:2019-03-30 15:20:30</li>
     * <li>node="day",num=1L:2019-03-31 10:20:30</li>
     * <li>node="year",num=1L:2020-03-30 10:20:30</li>
     *
     * @param node                                year   ,"month","week","day","huor","minute","second"
     * @param num                           +            -
     * @param dateTimeFormatter the 日期时间formatter
     * @return the 结果
     */
    public static String getAfterOrPreNowTime(String node, Long num, DateTimeFormatter dateTimeFormatter) {
        LocalDateTime now = LocalDateTime.now();
        if (HOUR.equals(node)) {
            return now.plusHours(num).format(dateTimeFormatter);
        } else if (DAY.equals(node)) {
            return now.plusDays(num).format(dateTimeFormatter);
        } else if (WEEK.equals(node)) {
            return now.plusWeeks(num).format(dateTimeFormatter);
        } else if (MONTH.equals(node)) {
            return now.plusMonths(num).format(dateTimeFormatter);
        } else if (YEAR.equals(node)) {
            return now.plusYears(num).format(dateTimeFormatter);
        } else if (MINUTE.equals(node)) {
            return now.plusMinutes(num).format(dateTimeFormatter);
        } else if (SECOND.equals(node)) {
            return now.plusSeconds(num).format(dateTimeFormatter);
        } else {
            return "Node is Error!";
        }
    }
    /**
     * 获取指定时间前 num 天的日期列表（从当天往前推，含当天）。
     *
     * @param num 天数，必须大于 0
     * @return 日期列表
     */
    public static List<Date> getBeforeDate(final int num) {
        assert num > 0 : "num            1";
        LocalDate localDate = LocalDate.now();
        List<Date> dateList = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            dateList.add(Date.from(localDate.minusDays(i).atStartOfDay().atZone(DEFAULT_ZONE_ID).toInstant()));
        }
        return dateList;
    }
    /**
     * 获取当前日期（格式：yyyy-MM-dd）。
     *
     * @return 当前日期字符串
     */
    public static String getCurrentDate() {
        return currentString();
    }
    /**
     * 计算指定时间距下一个整分钟还有多少毫秒。
     *
     * @param rightNow 当前时间戳（毫秒）
     * @return 距下一分钟的毫秒数
     */
    public static int getDelayToNextMinute(long rightNow) {
        return (int) (MILLISECONDS_PER_MINUTE - (rightNow % MILLISECONDS_PER_MINUTE));
    }
    /**
     * 计算指定时间上一个个完整分钟的毫秒时间戳。
     *
     * @param rightNow 当前时间戳（毫秒）
     * @return 上一个整分钟的毫秒时间戳
     */
    public static long getPreMinuteMills(long rightNow) {
        return rightNow - (rightNow % MILLISECONDS_PER_MINUTE) - 1;
    }
    /**
     * 获取当前系统时间毫秒数。
     *
     * @return 当前毫秒时间戳
     */
    public static long getTimeMillis() {
        return System.currentTimeMillis();
    }
    /**
     * 获取当前系统纳秒数。
     *
     * @return 当前纳秒时间戳
     */
    public static long getTimeNanos() {
        return System.nanoTime();
    }
    /**
     * 从多个候选日期字符串中解析，并找出最接近目标 本地日期时间 的那个。
     *
     * @param localTime      目标时间
     * @param localTimeStirs 候选时间字符串列表
     * @return 最接近的 本地日期时间
     */
    public static LocalDateTime nearLocalDateTime(LocalDateTime localTime, String... localTimeStirs) {
        LocalDateTime[] localTimes = new LocalDateTime[localTimeStirs.length];
        int index = 0;
        for (String timeStir : localTimeStirs) {
            localTimes[index] = LocalDateTime.parse(timeStir);
        }
        return nearLocalDateTime(localTime, localTimes);
    }
    /**
     *
     *
     * @param localDateTime the 本地日期时间
     * @param localDateTimes the 本地日期时间
     * @return the 结果
     */
    public static LocalDateTime nearLocalDateTime(LocalDateTime localDateTime, LocalDateTime... localDateTimes) {
        Map<Long, LocalDateTime> result = new HashMap<>(localDateTimes.length);
        long nanoOfDay = localDateTime.atZone(DEFAULT_ZONE_ID).toInstant().toEpochMilli();
        for (LocalDateTime time : localDateTimes) {
            result.put(Math.abs(nanoOfDay - time.getNano()), time);
        }
        LocalDateTime minLocalTime = null;
        long min = Long.MAX_VALUE;
        for (Map.Entry<Long, LocalDateTime> entry : result.entrySet()) {
            Long key = entry.getKey();
            if (key < min) {
                min = key;
                minLocalTime = entry.getValue();
            }
        }
        return minLocalTime;
    }
    /**
     * 找到与目标 本地时间 最接近的时间。
     *
     * @param localTime      目标时间
     * @param localTimeStirs 候选时间字符串列表
     * @return 最接近的 本地时间
     */
    public static LocalTime nearLocalTime(LocalTime localTime, String... localTimeStirs) {
        LocalTime[] localTimes = new LocalTime[localTimeStirs.length];
        int index = 0;
        for (String timeStir : localTimeStirs) {
            localTimes[index++] = LocalTime.parse(timeStir);
        }
        return nearLocalTime(localTime, localTimes);
    }
    /**
     *
     *
     * @param localTime the 本地时间
     * @param localTimes the 本地时间
     * @return the 结果
     */
    public static LocalTime nearLocalTime(LocalTime localTime, LocalTime... localTimes) {
        Map<Long, LocalTime> result = new HashMap<>(localTimes.length);
        long secondOfDay = localTime.toSecondOfDay();
        for (LocalTime time : localTimes) {
            result.put(Math.abs(secondOfDay - time.toSecondOfDay()), time);
        }
        LocalTime minLocalTime = null;
        long min = Long.MAX_VALUE;
        for (Map.Entry<Long, LocalTime> entry : result.entrySet()) {
            Long key = entry.getKey();
            if (key < min) {
                min = key;
                minLocalTime = entry.getValue();
            }
        }
        return minLocalTime;
    }
    /**
     * 以宽松模式从候选格式列表中解析日期字符串，返回 Calendar。
     *
     * @param str 日期时间字符串
     * @return 解析后的 Calendar
     * @throws ParseException 解析失败时抛出
     */
    public static Calendar parseCalendar(String str) throws ParseException {
        Calendar calendar = getInstance();
        calendar.setTime(parseDateWithLeniency(str, DATE_FORMATS, true));
        return calendar;
    }
    /**
     * 按多个候选日期格式解析字符串，首个匹配成功则返回，解析失败时抛出 {@link ParseException}。
     *
     * @param str             日期时间字符串
     * @param parsePatterns   候选日期格式数组，如 {"yyyy-MM-dd", "yyyymmdd"}
     * @return 解析后的 日期
     * @throws ParseException 所有格式均无法匹配时抛出
     */
    public static Date parseDate(String str, String[] parsePatterns) throws ParseException {
        return parseDateWithLeniency(str, parsePatterns, true);
    }
    /**
     * 自动推断格式解析日期时间字符串。支持数字时间戳（长度 > 10）及常见日期格式。
     *
     * @param str 日期时间字符串
     * @return 解析后的 日期，可能为 空
     * @throws IllegalArgumentException 若 str 为 空 或空字符串
     */
    public static Date parseDate(String str) throws ParseException {
        if(StringUtils.isNullOrEmpty(str)) {
            throw new IllegalArgumentException("str is null");
        }
        if (str.length() > NUMBER_10 && NumberUtils.isNumber(str)) {
            return new Date(Long.parseLong(str));
        }
        Date date = null;
        try {
            date = parseDateWithLeniency(str, DATE_FORMATS, false);
        } catch (ParseException ignored) {
        }
        return null == date ? parseDateWithLeniency(str, DATE_FORMATS, Locale.US) : date;
    }
    /**
     * 安全解析日期时间字符串，失败时返回 空。
     *
     * @param str 日期时间字符串
     * @return 解析后的 日期，解析失败返回 空
     */
    public static Date parseDateSafe(String str)  {
        Date date = null;
        try {
            date = parseDate(str);
        } catch (ParseException e) {
            return null;
        }
        if (null == date) {
            return null;
        }
        return date;
    }
    /**
     * 安全解析日期时间字符串，返回对应的 本地日期时间，失败时返回 空。
     *
     * @param str 日期时间字符串
     * @return 解析后的 本地日期时间，解析失败返回 空
     */
    public static LocalDateTime parseLocalDateTimeSafe(String str)  {
        Date date = parseDateSafe(str);
        if (null == date) {
            return null;
        }
        return toLocalDateTime(date);
    }
    /**
     * 按指定单格式解析日期时间字符串。
     *
     * @param str          日期时间字符串
     * @param parsePattern 单个日期格式，如 "yyyy-MM-dd HH:mm:ss"
     * @return 解析后的 日期
     * @throws ParseException 格式不匹配时抛出
     */
    public static Date parseDate(String str, final String parsePattern) throws ParseException {
        return parseDateWithLeniency(str, new String[]{parsePattern}, true);
    }
    /**
     * 从 轮次millis（支持秒级和毫秒级）创建 日期。
     *
     * <p>若长度为 10 则视为秒级时间戳，自动转换为毫秒。</p>
     *
     * @param epochMilli 时间戳（毫秒或秒）
     * @return 对应的 日期，空 时返回 空
     */
    public static Date parseDate(final Long epochMilli) {
        if (null == epochMilli) {
            return null;
        }
        int length = epochMilli.toString().length();
        long newLongValue = epochMilli;
        if (length == UNIX_LENGTH) {
            newLongValue *= ((Double) Math.pow(10D, MILLISECOND - length)).longValue();
        }
        return new Date(newLongValue);
    }
    /**
     * 本地日期 转为 日期（使用系统默认时区）。
     *
     * @param localDate 日期
     * @return 对应的 日期
     */
    public static Date parseDate(final LocalDate localDate) {
        return parseDate(localDate, null);
    }
    /**
     * 本地日期 -> 日期
     *
     * @param localDate 本地日期
     * @param zone      zone
     * @return Date
     */
    public static Date parseDate(final LocalDate localDate, final ZoneId zone) {
        if (null == localDate) {
            return null;
        }
        Instant instant = localDate.atStartOfDay(Optional.ofNullable(zone).orElse(DEFAULT_ZONE_ID)).toInstant();
        return Date.from(instant);
    }
    /**
     * Instant 转为 日期。
     *
     * @param instant 时间点
     * @return 对应的 日期
     */
    public static Date parseDate(final Instant instant) {
        return Date.from(Optional.ofNullable(instant).orElse(Instant.now()));
    }
    /**
     * 日期 -> 日期
     *
     * @param date 日期
     * @return Date
     */
    public static Date parseDate(final java.sql.Date date) {
        return new Date(date.getTime());
    }
    /**
     * 时间 -> 日期
     *
     * @param time 时间
     * @return Date
     */
    public static Date parseDate(final Time time) {
        return toDate(time.toLocalTime());
    }
    /**
     * Calendar 转为 日期。
     *
     * @param calendar 日历对象
     * @return 对应的 日期
     */
    public static Date parseDate(final Calendar calendar) {
        return toDate(calendar.toInstant());
    }
    /**
     * 本地日期 和 本地时间 -> 日期
     *
     * @param localDate 本地日期
     * @param localTime 本地时间
     * @param zone      zone
     * @return Date
     */
    public static Date parseDate(final LocalDate localDate, final LocalTime localTime, final ZoneId zone) {
        if (null == localDate || null == localTime) {
            return null;
        }
        LocalDateTime localDateTime = LocalDateTime.of(localDate, localTime);
        return parseDate(localDateTime, zone);
    }
    /**
     * 本地日期时间 转为 日期（使用系统默认时区）。
     *
     * @param localDateTime 日期时间
     * @return 对应的 日期
     */
    public static Date parseDate(final LocalDateTime localDateTime) {
        return parseDate(localDateTime, null);
    }
    /**
     * 本地日期时间 -> 日期
     *
     * @param localDateTime 本地时间
     * @param zone          zone
     * @return Date
     */
    public static Date parseDate(final LocalDateTime localDateTime, final ZoneId zone) {
        if (null == localDateTime) {
            return null;
        }
        Instant instant = localDateTime.atZone(Optional.ofNullable(zone).orElse(DEFAULT_ZONE_ID)).toInstant();
        return Date.from(instant);
    }
    /**
     * 以宽松模式解析日期时间字符串，支持多格式候选。
     *
     * @param str          日期时间字符串
     * @param parsePatterns 候选日期格式数组
     * @param loc           地区设置（区域），影响月份名等解析
     * @return 解析后的 日期，无法解析返回 空
     * @throws IllegalArgumentException 若 str 或 解析模式 为 空
     * @throws ParseException
     */
    public static Date parseDateWithLeniency(String str, String[] parsePatterns, Locale loc) throws ParseException {
        for (String dateFormat : parsePatterns) {
            SimpleDateFormat sdf2 = new SimpleDateFormat(dateFormat, loc);
            Date parse = null;
            try {
                parse = sdf2.parse(str);
            } catch (ParseException ignored) {
            }
            if (null != parse) {
                return parse;
            }
        }
        return null;
    }
    /**
     * <p>                                                                        </ p>
     * <p>
     *
     * 解析异常   </ p>
     *
     * @param str                                空
     * @param parsePatterns                                           简单日期格式化         空
     * @param lenient             /
     * @return Date
     * @throws IllegalArgumentException                                        空
     * @throws ParseException
     * @see Calendar
     */
    public static Date parseDateWithLeniency(String str, String[] parsePatterns, boolean lenient) throws ParseException {
        return parseDateWithLeniency(str, parsePatterns, lenient, null);
    }
    /**
     * <p>                                                                        </ p>
     * <p>
     *
     * 解析异常   </ p>
     *
     * @param str                                空
     * @param parsePatterns                                           简单日期格式化         空
     * @param lenient             /
     * @param loc the loc
     * @return Date
     * @throws IllegalArgumentException                                        空
     * @throws ParseException
     * @see Calendar
     */
    public static Date parseDateWithLeniency(String str, String[] parsePatterns, boolean lenient, Locale loc) throws ParseException {
        if (str == null || parsePatterns == null) {
            throw new IllegalArgumentException("Date and Patterns must not be null");
        }
        SimpleDateFormat parser = new SimpleDateFormat();
        parser.setLenient(lenient);
        if (null != loc) {
            parser.setCalendar(Calendar.getInstance(TimeZone.getDefault(), loc));
        }
        ParsePosition pos = new ParsePosition(0);
        for (String parsePattern : parsePatterns) {
            String pattern = parsePattern;
            // LANG-530 - need to make sure 'ZZ' output doesn't get passed to SimpleDateFormat
            if (parsePattern.endsWith("ZZ")) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            parser.applyPattern(pattern);
            pos.setIndex(0);
            String str2 = str;
            // LANG-530 - need to make sure 'ZZ' output doesn't hit SimpleDateFormat as it will ParseException
            if (parsePattern.endsWith("ZZ")) {
                int signIdx = indexOfSignChars(str2, 0);
                while (signIdx >= 0) {
                    str2 = reformatTimezone(str2, signIdx);
                    signIdx = indexOfSignChars(str2, ++signIdx);
                }
            }
            Date date = parser.parse(str2, pos);
            if (date != null && pos.getIndex() == str2.length()) {
                return date;
            }
        }
        str = trimAllWhitespace(str).toUpperCase();
        if (str.endsWith(SYMBOL_VIRTUAL_D)) {
            str += "0S";
        }
        Matcher matcher = PATTERN.matcher("P".concat(str.contains("D") ? str.replace("D", "DT") : "T".concat(str)));
        if (matcher.matches()) {
            if (!SYMBOL_VIRTUAL_T.equals(matcher.group(THIRD))) {
                boolean negate = "-".equals(matcher.group(1));
                String dayMatch = matcher.group(2);
                String hourMatch = matcher.group(4);
                String minuteMatch = matcher.group(5);
                String secondMatch = matcher.group(6);
                if (dayMatch != null || hourMatch != null || minuteMatch != null || secondMatch != null) {
                    long daysAsSecs = parseNumber(str, dayMatch, SECONDS_PER_DAY, "days");
                    long hoursAsSecs = parseNumber(str, hourMatch, SECONDS_PER_HOUR, "hours");
                    long minsAsSecs = parseNumber(str, minuteMatch, SECONDS_PER_MINUTE, "minutes");
                    long seconds = parseNumber(str, secondMatch, 1, "seconds");
                    try {
                        long secondsOfPt = createSeconds(negate, daysAsSecs, hoursAsSecs, minsAsSecs, seconds, 0);
                        if (daysAsSecs == 0) {
                            return toDate(toDay(LocalTime.ofSecondOfDay(secondsOfPt)));
                        } else {
                            LocalDateTime localDateTime = LocalDateTime.now();
                            localDateTime = localDateTime.plusSeconds(secondsOfPt);
                            return toDate(localDateTime);
                        }
                    } catch (ArithmeticException ex) {
                        throw new DateTimeParseException("Text cannot be parsed to a Duration: overflow", str, 0, ex);
                    }
                }
            }
        }
        throw new ParseException("Unable to parse the date: " + str, -1);
    }
    /**
     * yearmonth   日期
     * day的month         1   31                                                      2         29            28
     * 转为日期结束的month(yearmonth)
     *
     * @param yearMonth yearmonth
     * @return Date
     */
    public static Date parserDate(YearMonth yearMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth");
        return toDate(yearMonth.atDay(1));
    }
    /**
     * yearmonth   日期
     * day的month         1   31                                                      2         29            28
     * 转为日期结束的month(yearmonth)
     *
     * @param yearMonth  yearmonth
     * @param dayOfMonth the day的month
     * @return Date
     */
    public static Date parserDate(YearMonth yearMonth, int dayOfMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth");
        return toDate(yearMonth.atDay(dayOfMonth));
    }
    /**
     * zoned日期时间   日期
     *
     *
     * @param zonedDateTime zoned日期时间
     * @return Date
     */
    public static Date parserDate(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return Date.from(zonedDateTime.toInstant());
    }
    /**
     * temporalaccessor（本地日期/本地日期时间）转为 日期。
     *
     * @param temporalAccessor 时间访问器
     * @return 对应的 日期，不支持的类型返回 空
     */
    public static Date toDate(TemporalAccessor temporalAccessor) {
        if (null == temporalAccessor) {
            return null;
        }
        if(temporalAccessor instanceof LocalDateTime) {
            return toDate((LocalDateTime)temporalAccessor);
        }
        if(temporalAccessor instanceof LocalDate) {
            return toDate((LocalDate)temporalAccessor);
        }
        return null;
    }
    /**
     * 本地时间 转为 日期（取当天对应时刻，日期部分为 轮次）。
     *
     * @param localTime 时间
     * @return 对应的 日期
     */
    public static Date toDate(LocalTime localTime) {
        if (null == localTime) {
            return null;
        }
        return toDate(Instant.ofEpochSecond(localTime.getSecond(), localTime.getNano()));
    }
    /**
     * 本地日期 转为 日期（使用系统默认时区）。
     *
     * @param localDate 日期
     * @return 对应的 日期，本地日期 为 空 返回 空
     */
    public static Date toDate(LocalDate localDate) {
        if (null == localDate) {
            return null;
        }
        ZonedDateTime zonedDateTime = localDate.atStartOfDay(DEFAULT_ZONE_ID);
        Instant instant = zonedDateTime.toInstant();
        return Date.from(instant);
    }
    /**
     * 本地日期时间 转为 日期（使用系统默认时区）。
     *
     * @param localDateTime 日期时间
     * @return 对应的 日期
     */
    public static Date toDate(LocalDateTime localDateTime) {
        return toDate(localDateTime, DEFAULT_ZONE_ID);
    }
    /**
     * 本地日期时间 在指定时区下转为 日期。
     *
     * @param localDateTime 日期时间
     * @param zoneId        时区，为 空 时使用系统默认时区
     * @return 对应的 日期
     */
    public static Date toDate(LocalDateTime localDateTime, ZoneId zoneId) {
        if (null == localDateTime) {
            return null;
        }
        return Date.from(localDateTime.atZone(Optional.ofNullable(zoneId).orElse(DEFAULT_ZONE_ID)).toInstant());
    }
    /**
     * 本地日期 在指定时区下转为 日期（日期部分取当天 00:00:00）。
     *
     * @param localDate 日期
     * @param zoneId    时区，为 空 时使用系统默认时区
     * @return 对应的 日期
     */
    public static Date toDate(LocalDate localDate, ZoneId zoneId) {
        if (null == localDate) {
            return null;
        }
        return Date.from(localDate.atStartOfDay().atZone(Optional.ofNullable(zoneId).orElse(DEFAULT_ZONE_ID)).toInstant());
    }
    /**
     * Java.SQL.日期 直接返回（类型兼容转换）。
     *
     * @param date SQL 日期
     * @return 传入的日期对象
     */
    public static Date toDate(java.sql.Date date) {
        return date;
    }
    /**
     * 时间戳 直接返回（类型兼容转换）。
     *
     * @param date SQL 时间戳
     * @return 传入的时间戳对象
     */
    public static Date toDate(Timestamp date) {
        return date;
    }
    /**
     * Instant 转为 日期。
     *
     * @param instant 时间点，为 空 时返回 空
     * @return 对应的 日期
     */
    public static Date toDate(Instant instant) {
        if (null == instant) {
            return null;
        }
        return Date.from(instant);
    }
    /**
     * 持续时间 相对于当前时间的偏移量，计算出一个过去的 日期。
     *
     * @param duration 时长
     * @return 距离当前时间 持续时间 之前的 日期
     */
    public static Date toDate(Duration duration) {
        return new Date(System.currentTimeMillis() - duration.toMillis());
    }
    /**
     * long 毫秒时间戳转为 日期。
     *
     * @param time 毫秒时间戳
     * @return 对应的 日期
     */
    public static Date toDate(long time) {
        return Date.from(Instant.ofEpochMilli(complementMilliseconds(time)));
    }
    /**
     * 日期 转为 轮次 毫秒时间戳。
     *
     * @param date 日期
     * @return epoch 毫秒数
     * @throws NullPointerException 若 日期 为 空
     */
    public static long toEpochMilli(Date date) {
        Objects.requireNonNull(date, "date");
        return date.getTime();
    }
    /**
     * 时间戳
     *    1970-01-01T00:00:00Z
     *
     * @param timestamp 时间戳
     * @return the 结果
     */
    public static long toEpochMilli(Timestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        return timestamp.getTime();
    }
    /**
     * 本地日期时间 转为 轮次 毫秒时间戳（使用系统默认时区）。
     *
     * @param localDateTime 日期时间
     * @return epoch 毫秒数
     */
    public static long toEpochMilli(LocalDateTime localDateTime) {
        return toInstant(localDateTime).toEpochMilli();
    }
    /**
     * 本地日期 转为 轮次 毫秒时间戳（使用系统默认时区，日期部分为当天 00:00:00）。
     *
     * @param localDate 日期
     * @return epoch 毫秒数
     */
    public static long toEpochMilli(LocalDate localDate) {
        return toInstant(localDate).toEpochMilli();
    }
    /**
     * Instant
     *    1970-01-01T00:00:00Z
     *
     * @param instant Instant
     * @return the 结果
     */
    public static long toEpochMilli(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return instant.toEpochMilli();
    }
    /**
     * zoned日期时间 转为 轮次 毫秒时间戳。
     *
     * @param zonedDateTime 带时区的时间
     * @return epoch 毫秒数
     * @throws NullPointerException 若 zoned日期时间 为 空
     */
    public static long toEpochMilli(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toInstant().toEpochMilli();
    }
    /**
     * 日期 转为 Instant。
     *
     * @param date 日期
     * @return 对应的 Instant
     * @throws NullPointerException 若 日期 为 空
     */
    public static Instant toInstant(Date date) {
        Objects.requireNonNull(date, "date");
        return date.toInstant();
    }
    /**
     * 时间戳   Instant
     *
     * @param timestamp 时间戳
     * @return Instant
     */
    public static Instant toInstant(Timestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        return timestamp.toInstant();
    }
    /**
     * 本地日期时间 转为 Instant（使用系统默认时区）。
     *
     * @param localDateTime 日期时间
     * @return 对应的 Instant
     * @throws NullPointerException 若 本地日期时间 为 空
     */
    public static Instant toInstant(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return localDateTime.atZone(DEFAULT_ZONE_ID).toInstant();
    }
    /**
     * 本地日期 转为 Instant（使用系统默认时区，时间为当天 00:00:00）。
     *
     * @param localDate 日期
     * @return 对应的 Instant
     */
    public static Instant toInstant(LocalDate localDate) {
        return toLocalDateTime(localDate).atZone(DEFAULT_ZONE_ID).toInstant();
    }
    /**
     * 本地时间 转为 Instant（使用系统默认时区，日期为 轮次）。
     *
     * @param localTime 时间
     * @return 对应的 Instant
     */
    public static Instant toInstant(LocalTime localTime) {
        return toLocalDateTime(localTime).atZone(DEFAULT_ZONE_ID).toInstant();
    }
    /**
     * 轮次 毫秒时间戳转为 Instant。
     *
     * @param epochMilli 毫秒时间戳
     * @return 对应的 Instant
     * @throws NullPointerException 若 轮次milli 为 空（装箱后）
     */
    public static Instant toInstant(long epochMilli) {
        Objects.requireNonNull(epochMilli, "epochMilli");
        return Instant.ofEpochMilli(epochMilli);
    }
    /**
     * temporal   Instant
     *
     * @param temporal temporalaccessor
     * @return Instant
     */
    public static Instant toInstant(TemporalAccessor temporal) {
        return Instant.from(temporal);
    }
    /**
     * zoned日期时间 转为 Instant。
     *
     * @param zonedDateTime 带时区的时间
     * @return 对应的 Instant
     * @throws NullPointerException 若 zoned日期时间 为 空
     */
    public static Instant toInstant(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toInstant();
    }
    /**
     * 日期 转为 本地日期（使用系统默认时区）。
     *
     * @param date 日期
     * @return 对应的 本地日期
     */
    public static LocalDate toLocalDate(final Date date) {
        return toLocalDateTime(date).toLocalDate();
    }
    /**
     * 日期str   本地日期
     *
     * @param dateStr 日期str
     * @return LocalDate
     * @throws NullPointerException if 日期 是否 not valid
     */
    public static LocalDate toLocalDate(final String dateStr) throws ParseException {
        return toLocalDateTime(dateStr).toLocalDate();
    }
    /**
     * Calendar 转为 本地日期。
     *
     * @param calendar 日历对象
     * @return 对应的 本地日期
     */
    public static LocalDate toLocalDate(final Calendar calendar) {
        return toLocalDate(calendar.getTime());
    }
    /**
     * 字符串   本地日期
     *
     * @param str str
     * @param format 格式化
     * @return LocalDate
     * @throws NullPointerException if 本地日期时间 是否 not valid
     */
    public static LocalDate toLocalDate(String str, String format) {
        Objects.requireNonNull(str, "str");
        Objects.requireNonNull(format, "format");
        return toLocalDate(DateTimeFormatter.ofPattern(format).parse(str));
    }
    /**
     * 本地日期时间   本地日期
     *
     * @param localDateTime 本地日期时间
     * @return LocalDate
     * @throws NullPointerException if 本地日期时间 是否 not valid
     */
    public static LocalDate toLocalDate(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return localDateTime.toLocalDate();
    }
    /**
     * Instant   本地日期
     *
     * @param instant Instant
     * @return LocalDate
     */
    public static LocalDate toLocalDate(Instant instant) {
        return toLocalDateTime(instant).toLocalDate();
    }
    /**
     * 轮次milli         本地日期
     *
     * @param epochMilli the 轮次milli
     * @return LocalDate
     */
    public static LocalDate toLocalDate(long epochMilli) {
        Objects.requireNonNull(epochMilli, "epochMilli");
        return toLocalDateTime(epochMilli).toLocalDate();
    }
    /**
     * temporal   本地日期
     *
     * @param temporal temporalaccessor
     * @return LocalDate
     */
    public static LocalDate toLocalDate(TemporalAccessor temporal) {
        return LocalDate.from(temporal);
    }
    /**
     * zoned日期时间   本地日期
     *
     *
     * @param zonedDateTime zoned日期时间
     * @return LocalDate
     */
    public static LocalDate toLocalDate(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toLocalDate();
    }
    /**
     * yearmonth 转为 本地日期（指定日期）。
     *
     * @param yearMonth  年月对象
     * @param dayOfMonth 日期（1~31）
     * @return 对应的 本地日期
     * @throws NullPointerException 若 yearmonth 为 空
     */
    public static LocalDate toLocalDate(YearMonth yearMonth, int dayOfMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth");
        return yearMonth.atDay(dayOfMonth);
    }
    /**
     * yearmonth 转为当月最后一天的 本地日期。
     *
     * @param yearMonth 年月对象
     * @return 对应月的最后一天
     * @throws NullPointerException 若 yearmonth 为 空
     */
    public static LocalDate toLocalDateEndOfMonth(YearMonth yearMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth");
        return yearMonth.atEndOfMonth();
    }
    /**
     * yearmonth 转为当月的第一天（即 1 号）。
     *
     * @param yearMonth 年月对象
     * @return 对应月的第一天
     */
    public static LocalDate toLocalDateStartOfMonth(YearMonth yearMonth) {
        return toLocalDate(yearMonth, 1);
    }
    /**
     * 日期 转为 本地日期时间（使用系统默认时区）。
     *
     * @param date 日期
     * @return 对应的 本地日期时间
     */
    public static LocalDateTime toLocalDateTime(final Date date) {
        return toLocalDateTime(date, DEFAULT_ZONE_ID);
    }
    /**
     * Calendar 转为 本地日期时间。
     *
     * @param calendar 日历对象
     * @return 对应的 本地日期时间
     */
    public static LocalDateTime toLocalDateTime(final Calendar calendar) {
        return toLocalDateTime(calendar.getTime());
    }
    /**
     * 持续时间 转为 本地日期时间（取当前时间减去 持续时间 的时刻）。
     *
     * @param duration 时长
     * @return 对应的 本地日期时间
     */
    public static LocalDateTime toLocalDateTime(Duration duration) {
        return toLocalDateTime(toDate(duration));
    }
    /**
     * 将字符串按指定格式解析为 本地日期时间。
     *
     * @param date    日期字符串
     * @param pattern 日期格式，为 空 时使用默认格式 {@code yyyy-MM-dd HH:mm:ss}
     * @return 解析后的 本地日期时间，解析失败返回 空
     */
    public static LocalDateTime toLocalDateTime(final String date, final String pattern) {
        try {
            return toLocalDateTime(parseDate(date, Optional.ofNullable(pattern).orElse(DateFormatConstant.YYYY_MM_DD_HH_MM_SS)));
        } catch (ParseException e) {
            log.error("", e);
        }
        return null;
    }
    /**
     * 日期 -> 本地日期时间
     *
     * @param date 日期
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(final String date) throws ParseException {
        return toLocalDateTime(parseDate(date));
    }
    /**
     * 时间戳   本地日期时间
     *
     * @param timestamp 时间戳
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        return timestamp.toLocalDateTime();
    }
    /**
     * 本地日期 转为 本地日期时间（时间为当天 00:00:00）。
     *
     * @param localDate 日期
     * @return 对应的 本地日期时间
     * @throws NullPointerException 若 本地日期 为 空
     */
    public static LocalDateTime toLocalDateTime(LocalDate localDate) {
        Objects.requireNonNull(localDate, "localDate");
        return localDate.atStartOfDay();
    }
    /**
     * 本地日期 与 本地时间 组合为 本地日期时间。
     *
     * @param localDate 日期
     * @param localTime 时间
     * @return 组合后的 本地日期时间
     * @throws NullPointerException 若 本地日期 为 空
     */
    public static LocalDateTime toLocalDateTime(LocalDate localDate, LocalTime localTime) {
        Objects.requireNonNull(localDate, "localDate");
        return LocalDateTime.of(localDate, localTime);
    }
    /**
     * 本地时间 转为 本地日期时间（日期为当天）。
     *
     * @param localTime 时间
     * @return 对应的 本地日期时间
     * @throws NullPointerException 若 本地时间 为 空
     */
    public static LocalDateTime toLocalDateTime(LocalTime localTime) {
        Objects.requireNonNull(localTime, "localTime");
        return LocalDate.now().atTime(localTime);
    }
    /**
     * Instant 在系统默认时区下转为 本地日期时间。
     *
     * @param instant 时间点
     * @return 对应的 本地日期时间
     */
    public static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, DEFAULT_ZONE_ID);
    }
    /**
     * 轮次 毫秒时间戳转为 本地日期时间（使用系统默认时区）。
     *
     * @param epochMilli 毫秒时间戳
     * @return 对应的 本地日期时间
     * @throws NullPointerException 若 轮次milli 为 空（装箱后）
     */
    public static LocalDateTime toLocalDateTime(long epochMilli) {
        Objects.requireNonNull(epochMilli, "epochMilli");
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), DEFAULT_ZONE_ID);
    }
    /**
     * temporal   本地日期时间
     *
     * @param temporal temporalaccessor
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(TemporalAccessor temporal) {
        return LocalDateTime.from(temporal);
    }
    /**
     * zoned日期时间 转为 本地日期时间。
     *
     * @param zonedDateTime 带时区的时间
     * @return 对应的 本地日期时间（去掉时区信息）
     * @throws NullPointerException 若 zoned日期时间 为 空
     */
    public static LocalDateTime toLocalDateTime(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toLocalDateTime();
    }
    /**
     * 日期 -> 本地日期时间
     *
     * @param date 日期
     * @param zone zone
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(final Date date, final ZoneId zone) {
        if (null == date) {
            return null;
        }
        if(date instanceof java.sql.Date) {
            return toLocalDateTime(date.getTime());
        }
        Instant instant = date.toInstant();
        return LocalDateTime.ofInstant(instant, Optional.ofNullable(zone).orElse(DEFAULT_ZONE_ID));
    }
    /**
     * 日期 -> 本地日期时间
     *
     * @param date 日期
     * @param zone zone
     * @return LocalDateTime
     */
    public static LocalTime toLocalTime(final Date date, final ZoneId zone) {
        return toLocalDateTime(date, zone).toLocalTime();
    }
    /**
     * Calendar -> 本地日期时间
     *
     * @param calendar 日期
     * @return LocalDateTime
     * @throws NullPointerException if calendar 是否 not valid
     */
    public static LocalTime toLocalTime(final Calendar calendar) {
        return toLocalDateTime(calendar.getTime()).toLocalTime();
    }
    /**
     * 持续时间 -> 本地时间
     *
     * @param duration 持续时间
     * @return LocalTime
     */
    public static LocalTime toLocalTime(Duration duration) {
        return toLocalTime(toDate(duration));
    }
    /**
     * 日期str -> 本地日期时间
     *
     * @param dateStr 日期str
     * @return LocalDateTime
     * @throws NullPointerException if 日期str 是否 not valid
     */
    public static LocalTime toLocalTime(final String dateStr) throws ParseException {
        if (NumberUtils.isNumber(dateStr)) {
            return LocalTime.ofSecondOfDay(Long.parseLong(dateStr));
        }
        return toLocalDateTime(dateStr).toLocalTime();
    }
    /**
     * 日期   本地时间
     *
     * @param date 日期
     * @return LocalTime
     */
    public static LocalTime toLocalTime(Date date) {
        return toLocalDateTime(date).toLocalTime();
    }
    /**
     * long   本地时间
     *
     * @param epochMilli the 轮次milli
     * @return LocalTime
     */
    public static LocalTime toLocalTime(long epochMilli) {
        return toLocalTime(Instant.ofEpochMilli(epochMilli));
    }
    /**
     * 本地日期时间   本地时间
     *
     * @param localDateTime 本地日期时间
     * @return LocalTime
     */
    public static LocalTime toLocalTime(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return localDateTime.toLocalTime();
    }
    /**
     * Instant   本地时间
     *
     * @param instant Instant
     * @return LocalTime
     */
    public static LocalTime toLocalTime(Instant instant) {
        return toLocalDateTime(instant).toLocalTime();
    }
    /**
     * temporal   本地时间
     *
     * @param temporal temporalaccessor
     * @return LocalTime
     */
    public static LocalTime toLocalTime(TemporalAccessor temporal) {
        return LocalTime.from(temporal);
    }
    /**
     * zoned日期时间   本地时间
     *
     *
     * @param zonedDateTime zoned日期时间
     * @return LocalTime
     */
    public static LocalTime toLocalTime(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toLocalTime();
    }
    /**
     * 日期 转为 Java.SQL.时间。
     *
     * @param date 日期
     * @return 对应的 时间，丢弃日期部分
     * @throws NullPointerException 若 日期 为 空
     */
    public static Time toTime(Date date) {
        Objects.requireNonNull(date, "date");
        return new Time(date.getTime());
    }
    /**
     * 本地时间 转为 Java.SQL.时间。
     *
     * @param localTime 时间
     * @return 对应的 时间
     * @throws NullPointerException 若 本地时间 为 空
     */
    public static Time toTime(LocalTime localTime) {
        Objects.requireNonNull(localTime, "date");
        return new Time(toDate(localTime).getTime());
    }
    /**
     * 日期 转为 时间戳。
     *
     * @param date 日期
     * @return 对应的 时间戳
     * @throws NullPointerException 若 日期 为 空
     */
    public static Timestamp toTimestamp(Date date) {
        Objects.requireNonNull(date, "date");
        return new Timestamp(date.getTime());
    }
    /**
     * 本地日期时间 转为 时间戳。
     *
     * @param localDateTime 日期时间
     * @return 对应的 时间戳
     * @throws NullPointerException 若 本地日期时间 为 空
     */
    public static Timestamp toTimestamp(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return Timestamp.valueOf(localDateTime);
    }
    /**
     * Instant   时间戳
     *
     * @param instant Instant
     * @return Timestamp
     */
    public static Timestamp toTimestamp(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return Timestamp.from(instant);
    }
    /**
     * 轮次 毫秒时间戳转为 时间戳。
     *
     * @param epochMilli 毫秒时间戳
     * @return 对应的 时间戳
     */
    public static Timestamp toTimestamp(long epochMilli) {
        return new Timestamp(epochMilli);
    }
    /**
     * 日期 转为 yearmonth。
     *
     * @param date 日期
     * @return 对应的 yearmonth
     */
    public static YearMonth toYearMonth(Date date) {
        LocalDate localDate = toLocalDate(date);
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }
    /**
     * 本地日期时间 转为 yearmonth。
     *
     * @param localDateTime 日期时间
     * @return 对应的 yearmonth
     */
    public static YearMonth toYearMonth(LocalDateTime localDateTime) {
        LocalDate localDate = toLocalDate(localDateTime);
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }
    /**
     * 本地日期 转为 yearmonth。
     *
     * @param localDate 日期
     * @return 对应的 yearmonth
     * @throws NullPointerException 若 本地日期 为 空
     */
    public static YearMonth toYearMonth(LocalDate localDate) {
        Objects.requireNonNull(localDate, "localDate");
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }
    /**
     * Instant 转为 yearmonth（使用系统默认时区）。
     *
     * @param instant 时间点
     * @return 对应的 yearmonth
     */
    public static YearMonth toYearMonth(Instant instant) {
        LocalDate localDate = toLocalDate(instant);
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }
    /**
     * zoned日期时间 转为 yearmonth。
     *
     * @param zonedDateTime 带时区的时间
     * @return 对应的 yearmonth
     */
    public static YearMonth toYearMonth(ZonedDateTime zonedDateTime) {
        LocalDate localDate = toLocalDate(zonedDateTime);
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }
    /**
     * 日期 转为 zoned日期时间（使用系统默认时区）。
     *
     * @param date 日期
     * @return 对应的 zoned日期时间
     * @throws NullPointerException 若 日期 为 空
     */
    public static ZonedDateTime toZonedDateTime(Date date) {
        Objects.requireNonNull(date, "date");
        return Instant.ofEpochMilli(date.getTime()).atZone(DEFAULT_ZONE_ID);
    }
    /**
     * 日期 在指定时区字符串下转为 zoned日期时间。
     *
     * @param date   日期
     * @param zoneId 时区 标识，如 "Asia/Shanghai"
     * @return 对应的 zoned日期时间
     * @throws NullPointerException 若任一参数为 空
     */
    public static ZonedDateTime toZonedDateTime(Date date, String zoneId) {
        Objects.requireNonNull(zoneId, "zoneId");
        return toZonedDateTime(date, ZoneId.of(zoneId));
    }
    /**
     * 日期 在指定 zoneid 下转为 zoned日期时间。
     *
     * @param date 日期
     * @param zone 时区
     * @return 对应的 zoned日期时间
     * @throws NullPointerException 若任一参数为 空
     */
    public static ZonedDateTime toZonedDateTime(Date date, ZoneId zone) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(zone, "zone");
        return Instant.ofEpochMilli(date.getTime()).atZone(zone);
    }
    /**
     * 本地日期时间 转为 zoned日期时间（使用系统默认时区）。
     *
     * @param localDateTime 日期时间
     * @return 对应的 zoned日期时间
     * @throws NullPointerException 若 本地日期时间 为 空
     */
    public static ZonedDateTime toZonedDateTime(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return localDateTime.atZone(DEFAULT_ZONE_ID);
    }
    /**
     * 本地日期时间 在指定时区字符串下转为 zoned日期时间。
     *
     * @param localDateTime 日期时间
     * @param zoneId        时区 标识
     * @return 对应的 zoned日期时间
     * @throws NullPointerException 若任一参数为 空
     */
    public static ZonedDateTime toZonedDateTime(LocalDateTime localDateTime, String zoneId) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        Objects.requireNonNull(zoneId, "zoneId");
        return localDateTime.atZone(ZoneId.of(zoneId));
    }
    /**
     * 本地日期 转为 zoned日期时间（使用系统默认时区，时间为当天 00:00:00）。
     *
     * @param localDate 日期
     * @return 对应的 zoned日期时间
     * @throws NullPointerException 若 本地日期 为 空
     */
    public static ZonedDateTime toZonedDateTime(LocalDate localDate) {
        Objects.requireNonNull(localDate, "localDate");
        return localDate.atStartOfDay().atZone(DEFAULT_ZONE_ID);
    }
    /**
     * 本地时间 转为 zoned日期时间（日期为当天，使用系统默认时区）。
     *
     * @param localTime 时间
     * @return 对应的 zoned日期时间
     * @throws NullPointerException 若 本地时间 为 空
     */
    public static ZonedDateTime toZonedDateTime(LocalTime localTime) {
        Objects.requireNonNull(localTime, "localTime");
        return LocalDate.now().atTime(localTime).atZone(DEFAULT_ZONE_ID);
    }
    /**
     * Instant 转为 zoned日期时间（使用系统默认时区）。
     *
     * @param instant 时间点
     * @return 对应的 zoned日期时间
     */
    public static ZonedDateTime toZonedDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, DEFAULT_ZONE_ID).atZone(DEFAULT_ZONE_ID);
    }
    /**
     * 轮次 毫秒时间戳转为 zoned日期时间（使用系统默认时区）。
     *
     * @param epochMilli 毫秒时间戳
     * @return 对应的 zoned日期时间
     * @throws NullPointerException 若 轮次milli 为 空（装箱后）
     */
    public static ZonedDateTime toZonedDateTime(long epochMilli) {
        Objects.requireNonNull(epochMilli, "epochMilli");
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), DEFAULT_ZONE_ID)
                .atZone(DEFAULT_ZONE_ID);
    }
    /**
     * temporal   zoned日期时间
     *
     * @param temporal temporalaccessor
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(TemporalAccessor temporal) {
        return LocalDateTime.from(temporal).atZone(DEFAULT_ZONE_ID);
    }
    /**
     *
     *
     * @param localTime the 本地时间
     * @return the 结果
     */
    private static LocalDateTime toDay(LocalTime localTime) {
        return LocalDateTime.of(LocalDate.now(), localTime);
    }
    /**
     *
     *
     * @param negate the negate
     * @param daysAsSecs the daysassecs
     * @param hoursAsSecs the hoursassecs
     * @param minsAsSecs the 最小assecs
     * @param secs the secs
     * @param nanos the nano
     * @return the 结果
     */
    private static long createSeconds(boolean negate, long daysAsSecs, long hoursAsSecs, long minsAsSecs, long secs, int nanos) {
        long exact = Math.addExact(daysAsSecs, Math.addExact(hoursAsSecs, Math.addExact(minsAsSecs, secs)));
        return negate ? -1 * exact : exact;
    }
    /**
     *
     *
     * @param text the 文本
     * @param parsed the parsed
     * @param multiplier the multiplier
     * @param errorText the 错误文本
     * @return the 结果
     */
    private static long parseNumber(CharSequence text, String parsed, int multiplier, String errorText) {
 // regex 限制 转为 [-+]?[0-9]+
        if (parsed == null) {
            return 0;
        }
        try {
            long val = Long.parseLong(parsed);
            return Math.multiplyExact(val, multiplier);
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new DateTimeParseException("Text cannot be parsed to a Duration: " + errorText, text, 0, ex);
        }
    }
    /**
     * 索引 的 标志 charaters (i.e. '+' 或 '-').
     *
     * @param str the str
     * @param startPos the 启动采购订单
     * @return                                                             -1
     */
    private static int indexOfSignChars(String str, int startPos) {
        int idx = indexOf(str, '+', startPos);
        if (idx < 0) {
            idx = indexOf(str, '-', startPos);
        }
        if (idx < 0) {
            idx = indexOf(str, '/', startPos);
        }
        return idx;
    }
    /**
     *
     *
     * @param str the str
     * @param signIdx the 标志idx
     * @return the 结果
     */
    private static String reformatTimezone(String str, int signIdx) {
        String str2 = str;
        if (signIdx >= 0 &&
                signIdx + 5 < str.length() &&
                Character.isDigit(str.charAt(signIdx + 1)) &&
                Character.isDigit(str.charAt(signIdx + 2)) &&
                str.charAt(signIdx + 3) == ':' &&
                Character.isDigit(str.charAt(signIdx + 4)) &&
                Character.isDigit(str.charAt(signIdx + 5))) {
            str2 = str.substring(0, signIdx + 3) + str.substring(signIdx + 4);
        }
        return str2;
    }
    /**
     * 日期格式化
     *
     * @param pattern the 模式
     * @return DateFormat
     * @see DateFormat
     */
    private static DateFormat createDefaultDateFormat(String pattern) {
        return createDateFormat(pattern, null);
    }
    /**
     * 日期格式化
     *
     * @param pattern the 模式
     * @param timeZone the 时间zone
     * @return DateFormat
     * @see DateFormat
     */
    private static DateFormat createDateFormat(String pattern, String timeZone) {
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        if (null != timeZone) {
            TimeZone gmt = TimeZone.getTimeZone(timeZone);
            sdf.setTimeZone(gmt);
        }
        sdf.setLenient(true);
        return sdf;
    }
    /**
     *
     *
     * @param time the 时间
     * @return the 结果
     */
    private static long complementMilliseconds(long time) {
        String timeStr = time + "";
        int length = timeStr.length();
        if (length == MILLISECOND) {
            return time;
        }
        BigDecimal bigDecimal = new BigDecimal(time);
        if (length < MILLISECOND) {
            return bigDecimal.multiply(BigDecimal.TEN.pow(MILLISECOND - length)).longValue();
        }
        return bigDecimal.subtract(BigDecimal.TEN.pow(length - MILLISECOND)).longValue();
    }
    /**
     * 以指定参数创建 Calendar（月份从 0 开始）。
     *
     * @param year     年份
     * @param month    月份（1-12，内部减 1）
     * @param day      日期
     * @param hour     小时
     * @param minute   分钟
     * @param second   秒
     * @param milli    毫秒
     * @return 对应的 Calendar
     */
    public static Calendar toCalendar(int year, int month, int day, int hour, int minute, int second, int milli) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(DEFAULT_ZONE_ID));
        c.set(year, month - 1, day, hour, minute, second);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
    /**
     *
     *
     * @param year the year
     * @param month the month
     * @param day the day
     * @param hour the hour
     * @param minute the minute
     * @param second the second
     * @return the 结果
     */
    public static Calendar toCalendar(int year, int month, int day, int hour, int minute, int second) {
        return toCalendar(year, month, day, hour, minute, second, 0);
    }
    /**
     *
     *
     * @param year the year
     * @param month the month
     * @param day the day
     * @param hour the hour
     * @param minute the minute
     * @return the 结果
     */
    public static Calendar toCalendar(int year, int month, int day, int hour, int minute) {
        return toCalendar(year, month, day, hour, minute, 0, 0);
    }
    /**
     *
     *
     * @param year the year
     * @param month the month
     * @param day the day
     * @param hour the hour
     * @return the 结果
     */
    public static Calendar toCalendar(int year, int month, int day, int hour) {
        return toCalendar(year, month, day, hour, 0, 0, 0);
    }
    /**
     *
     *
     * @param year the year
     * @param month the month
     * @param day the day
     * @return the 结果
     */
    public static Calendar toCalendar(int year, int month, int day) {
        return toCalendar(year, month, day, 0, 0, 0);
    }
    /**
     *
     *
     * @param year the year
     * @param month the month
     * @return the 结果
     */
    public static Calendar toCalendar(int year, int month) {
        return toCalendar(year, month, 1, 0, 0, 0);
    }
    /**
     *
     *
     * @param date the 日期
     * @return the 结果
     */
    public static Calendar toCalendar(Date date) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(DEFAULT_ZONE_ID));
        c.setTime(date);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
    /**
     * <pre>
     *     trimAllWhitespace("test") = "test"
     *     trimAllWhitespace("test ") = "test"
     *     trimAllWhitespace(" test ") = "test"
     *     trimAllWhitespace(" te st ") = "test"
     *     trimAllWhitespace(null) = null
     * </pre>
     *
     * @param source the 源
     * @return the 结果
     */
    private static String trimAllWhitespace(String source) {
        if (null == source || source.length() == 0) {
            return source;
        } else {
            int len = source.length();
            StringBuilder sb = new StringBuilder(source.length());
            for (int i = 0; i < len; ++i) {
                char c = source.charAt(i);
                if (!Character.isWhitespace(c)) {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
    /**
     *
     *
     * @param str the str
     * @param searchChar the 搜索char
     * @param start                                 0      0
     * @return the 结果
     */
    private static int indexOf(CharSequence str, char searchChar, int start) {
        if (str instanceof String) {
            return ((String) str).indexOf(searchChar, start);
        } else {
            return indexOf(str, searchChar, start, -1);
        }
    }
    /**
     *
     *
     * @param str the str
     * @param searchChar the 搜索char
     * @param start                                 0      0
     * @param end                                   str.长度()
     * @return the 结果
     */
    private static int indexOf(final CharSequence str, char searchChar, int start, int end) {
        if (null == str) {
            return INDEX_NOT_FOUND;
        }
        final int len = str.length();
        if (start < 0 || start > len) {
            start = 0;
        }
        if (end > len || end < 0) {
            end = len;
        }
        for (int i = start; i < end; i++) {
            if (str.charAt(i) == searchChar) {
                return i;
            }
        }
        return INDEX_NOT_FOUND;
    }
    /**
     *
     *
     * @param unit the unit
     * @return the 结果
     */
    public static String getShotName(TimeUnit unit) {
        switch (unit) {
            case NANOSECONDS:
                return "ns";
            case MICROSECONDS:
                return "  s";
            case MILLISECONDS:
                return "ms";
            case SECONDS:
                return "s";
            case MINUTES:
                return "min";
            case HOURS:
                return "h";
            default:
                return unit.name().toLowerCase();
        }
    }
    /**
     *
     *
     * @param year the year
     * @return the 结果
     */
    public static boolean isLeapYear(int year) {
        return Year.isLeap(year);
    }
    /**
     * @return the 结果
     */
    public static Date currentDate() {
        return new Date();
    }
    /**
     * @return the 结果
     */
    public static int thisYear() {
        return year(currentDate());
    }
    /**
     *
     *
     * @param date the 日期
     * @return the 结果
     */
    public static int year(Date date) {
        Instant instant = date.toInstant();
        ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());
        return zonedDateTime.getYear();
    }
    /**
     * formatter          zoned日期时间
     *
     * @param text the 文本
     * @param formatter 日期时间formatter
     * @return ZonedDateTime
     */
    public static ZonedDateTime parseToZonedDateTime(String text, DateTimeFormatter formatter) {
        return ZonedDateTime.parse(text, formatter);
    }
    /**
     * 格式化时间
     *
     * @param inputTime 输入时间
     * @return 格式化时间的结果
     */
    public static String formatTime(LocalDateTime inputTime) {
        if (inputTime == null) {
            throw new IllegalArgumentException("Input time cannot be null");
        }
        LocalDateTime now = LocalDateTime.now();
        Duration duration = Duration.between(inputTime, now);
        if (duration.isNegative()) {
            return "      ";
        }
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = ChronoUnit.DAYS.between(inputTime, now);
        long months = ChronoUnit.MONTHS.between(inputTime, now);
        long years = ChronoUnit.YEARS.between(inputTime, now);
        if (years > 0) {
            return inputTime.format(DateFormatConstant.YYYY_MM_DD_FMT);
        }
        if (months > 0 || days > 0) {
            return inputTime.format(DateFormatConstant.MM_DD_EN_FMT);
        }
        if (hours > 0) {
            return hours + "         ";
        }
        if (minutes > 0) {
            return minutes + "         ";
        }
        return seconds + "      ";
    }
    /**
     * 向日期添加指定天数，支持 日期 / 本地日期 / 本地日期时间。
     *
     * @param date 基准日期，为 空 时返回 空
     * @param days 偏移天数，正数向后，负数向前
     * @param <T>  日期类型
     * @return 偏移后的日期对象
     */
    public static <T> T plusDay(T date, int days) {
        if (date == null) {
            return null;
        }
        try {
            if (date instanceof Date utilDate) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(utilDate);
                calendar.add(Calendar.DAY_OF_YEAR, days);
                return (T) calendar.getTime();
            } else if (date instanceof LocalDate localDate) {
                return (T) localDate.plusDays(days);
            } else if (date instanceof LocalDateTime localDateTime) {
                return (T) localDateTime.plusDays(days);
            } else {
                log.warn("                        : {}", date.getClass().getName());
                return date;
            }
        } catch (Exception e) {
            log.error("                  ", e);
            return date;
        }
    }
    /**
     * 向日期添加指定小时数，支持 日期 / 本地日期时间 / 本地时间。
     *
     * @param date  基准日期，为 空 时返回 空
     * @param hours 偏移小时数，正数向后，负数向前
     * @param <T>   日期类型
     * @return 偏移后的日期对象
     */
    public static <T> T plusHour(T date, int hours) {
        if (date == null) {
            return null;
        }
        try {
            if (date instanceof Date utilDate) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(utilDate);
                calendar.add(Calendar.HOUR_OF_DAY, hours);
                return (T) calendar.getTime();
            } else if (date instanceof LocalDateTime localDateTime) {
                return (T) localDateTime.plusHours(hours);
            } else if (date instanceof LocalTime localTime) {
                return (T) localTime.plusHours(hours);
            } else {
                log.warn("                        : {}", date.getClass().getName());
                return date;
            }
        } catch (Exception e) {
            log.error("                     ", e);
            return date;
        }
    }
    /**
     * 向日期添加指定分钟数，支持 日期 / 本地日期时间 / 本地时间。
     *
     * @param date    基准日期，为 空 时返回 空
     * @param minutes 偏移分钟数，正数向后，负数向前
     * @param <T>     日期类型
     * @return 偏移后的日期对象
     */
    public static <T> T plusMinute(T date, int minutes) {
        if (date == null) {
            return null;
        }
        try {
            if (date instanceof Date utilDate) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(utilDate);
                calendar.add(Calendar.MINUTE, minutes);
                return (T) calendar.getTime();
            } else if (date instanceof LocalDateTime localDateTime) {
                return (T) localDateTime.plusMinutes(minutes);
            } else if (date instanceof LocalTime localTime) {
                return (T) localTime.plusMinutes(minutes);
            } else {
                log.warn("                        : {}", date.getClass().getName());
                return date;
            }
        } catch (Exception e) {
            log.error("                     ", e);
            return date;
        }
    }
    /**
     * 判断给定日期是否落在指定日期区间内。
     *
     * <p>支持 Date / LocalDate / LocalDateTime / 字符串等可解析类型。</p>
     *
     * @param date      待判断的日期
     * @param startDate 区间起始日期
     * @param endDate   区间结束日期
     * @return 日期在区间内返回 true
     */
    public static boolean inDateRange(Object date, Object startDate, Object endDate) {
        if (date == null || startDate == null || endDate == null) {
            return false;
        }
        try {
            LocalDateTime checkDateTime = Converter.parseLocalDateTimeSafe(date);
            LocalDateTime start = Converter.parseLocalDateTimeSafe(startDate);
            LocalDateTime end = Converter.parseLocalDateTimeSafe(endDate);
            if (checkDateTime == null || start == null || end == null) {
                return false;
            }
            return !checkDateTime.isBefore(start) && !checkDateTime.isAfter(end);
        } catch (Exception e) {
            log.warn("                        ", e);
            return false;
        }
    }
    /**
     * 时间格式化器（ISO："HH:mm:ss"）
     */
    private static final DateTimeFormatter PARSE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    /**
     * 日期格式化器（ISO："yyyy-MM-dd"）
     */
    private static final DateTimeFormatter PARSE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /**
     * 宽松解析时间字符串为 {@link LocalTime}，解析失败返回 {@code null}。
     * <p>支持 ISO 格式（"10:15:30"）以及中文习惯格式（"10时15分30秒"、"10点15分"等）。</p>
     *
     * @param time 时间字符串
     * @return 本地时间，解析失败或参数为 空 时返回 {@code null}
     */
    public static LocalTime parseLocalTimeSafe(String time) {
        if (time == null) {
            return null;
        }
        String trimmed = time.trim();
        try {
            return LocalTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException e) {
            try {
                return LocalTime.parse(trimmed, PARSE_TIME_FORMATTER);
            } catch (DateTimeParseException ex) {
                String normalized = trimmed.replace("时", ":")
                        .replace("点", ":")
                        .replace("分", ":")
                        .replace("秒", "");
                try {
                    return LocalTime.parse(normalized, PARSE_TIME_FORMATTER);
                } catch (DateTimeParseException px) {
                    return null;
                }
            }
        }
    }
    /**
     * 宽松解析日期字符串为 {@link LocalDate}，解析失败返回 {@code null}。
     * <p>支持 ISO 格式（"2025-07-25"）以及中文习惯格式（"2025年07月25日"）。</p>
     *
     * @param date 日期字符串
     * @return 本地日期，解析失败或参数为 空 时返回 {@code null}
     */
    public static LocalDate parseLocalDateSafe(String date) {
        if (date == null) {
            return null;
        }
        String trimmed = date.trim();
        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(trimmed, PARSE_DATE_FORMATTER);
            } catch (DateTimeParseException ex) {
                String normalized = trimmed.replace("年", "-")
                        .replace("月", "-")
                        .replace("日", "");
                try {
                    return LocalDate.parse(normalized, PARSE_DATE_FORMATTER);
                } catch (DateTimeParseException px) {
                    return null;
                }
            }
        }
    }
}
