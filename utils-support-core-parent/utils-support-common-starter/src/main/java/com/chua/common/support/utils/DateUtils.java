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
 * @version 1.0.0
 * @since 2020/12/21
 */
@Slf4j
public class DateUtils {
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
    /** Accuracy_hours */
    public static final int ACCURACY_HOURS = 4;
    /** Accuracy_minutes */
    public static final int ACCURACY_MINUTES = 5;
    /** Accuracy_seconds */
    public static final int ACCURACY_SECONDS = 6;
    /** Accuracy_milliseconds */
    public static final int ACCURACY_MILLISECONDS = 7;
    /** Accuracy_milliseconds_forced */
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
    /** Default_zone_id */
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
     * Nanos per second.
     */
    static final long NANOS_PER_SECOND = 1000_000_000L;
    /**
     * Nanos per minute.
     */
    static final long NANOS_PER_MINUTE = NANOS_PER_SECOND * SECONDS_PER_MINUTE;
    /**
     * Nanos per hour.
     */
    static final long NANOS_PER_HOUR = NANOS_PER_MINUTE * MINUTES_PER_HOUR;
    /**
     * Nanos per day.
     */
    static final long NANOS_PER_DAY = NANOS_PER_HOUR * HOURS_PER_DAY;
    /**
     * unix
     */
    private static final int UNIX_LENGTH = 10;
    /**
     * Millisecond constant
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
     * Monday constant
     */
    private static final String MONDAY = "MONDAY";
    /**
     * Tuesday constant
     */
    private static final String TUESDAY = "TUESDAY";
    /**
     * Wednesday constant
     */
    private static final String WEDNESDAY = "WEDNESDAY";
    /**
     * Thursday constant
     */
    private static final String THURSDAY = "THURSDAY";
    /**
     * Friday constant
     */
    private static final String FRIDAY = "FRIDAY";
    /**
     * Saturday constant
     */
    private static final String SATURDAY = "SATURDAY";
    /**
     * Sunday constant
     */
    private static final String SUNDAY = "SUNDAY";
    /** 模式 */
    private static final Pattern PATTERN =
            Pattern.compile("([-+]?)P(?:([-+]?[0-9]+)D)?" +
                            "(T(?:([-+]?[0-9]+)H)?(?:([-+]?[0-9]+)M)?(?:([-+]?[0-9]+)(?:[.,]([0-9]{0,9}))?S)?)?",
                    Pattern.CASE_INSENSITIVE);
    /** DATE_FORMATS */
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
    /** Index_not_found */
    private static final int INDEX_NOT_FOUND = -1;

    /**
     *
     *
     * @param time the time
     * @return the result
     */
    public static Duration toDuration(String time) {
        return Duration.parse("PT" + time);
    }

    /**
     *
     *
     * @param time the time
     * @return the result
     */
    public static Period toPeriod(String time) {
        return Period.parse("P" + time);
    }

    /**
     *
     *
     * @param date the date
     * @param date1       1
     * @param dateUnit    dateUnit
     * @return long
     */
    public static long between(Date date, Date date1, DateUnit dateUnit) {
        return Math.abs(date.getTime() - date1.getTime()) / dateUnit.getMillis();
    }
    /**
     *
     *
     * @return boolean
     */
    public static boolean isDay() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);

        return hour >= 6 && hour < 18;
    }


    /**
     *       {date1}         {date2}
     *
     * @param date1 the date1
     * @param date2 the date2
     * @return {date1}   {date2}            true
     * @see NullPointerException
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
     *       {date1}         {date2}
     *
     * @param date1 the date1
     * @param date2 the date2
     * @return {date1}   {date2}            true
     * @see NullPointerException
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
     *       {date1}, {date2}
     *
     * @param date1 the date1
     * @param date2 the date2
     * @return {date1}, {date2}                  true
     * @see NullPointerException
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
     *       {date}   {beforeOrAfter}
     *
     * @param date the date
     * @param beforeOrAfter < 0    n   , > 0    n
     * @return    {beforeOrAfter}
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
     *       {date}
     *
     * @param date the date
     * @return the result
     */
    public Date getDayOfYearday(Date date) {
        return getDayOfBeforeOrAfter(date, -1);
    }

    /**
     *       {date}
     *
     * @param date the date
     * @return the result
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
     *       {date}
     *
     * @param date the date
     * @return the result
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
     * {date}
     *
     * @param date the date
     * @return the result
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
     *       {date}
     *
     * @param date the date
     * @return the result
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
     *       {date}
     *
     * @param date the date
     * @return the result
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
     * {date}
     *
     * @param date the date
     * @return the result
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
     *
     *
     * @return 1:      , 2:
     */
    public int getWeek() {
        Date today = new Date();
        Calendar c = getInstance();
        c.setTime(today);
        return c.get(DAY_OF_WEEK) - 1;
    }

    /**
     *
     *
     * @return 1:      , 2:
     */
    public int getWeek(Date date) {
        Calendar c = getInstance();
        c.setTime(date);
        return c.get(DAY_OF_WEEK) - 1;
    }

    /**
     *                                        (               )
     *
     * @param before the before
     * @param after the after
     * @return the result
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
     *
     *
     * @return the result
     */
    public static long current() {
        return System.currentTimeMillis();
    }

    /**
     *
     *
     * @return the result
     */
    public static String currentString() {
        return format(current(), DateFormatConstant.YYYY_MM_DD_HH_MM_SS);
    }

    /**
     *
     *
     * @return the result
     */
    public static String currentDateString() {
        return format(current(), DateFormatConstant.YYYY_MM_DD);
    }

    /**
     *
     * <p>
     * DateHelper.format(new Date()) =
     * </p>
     *
     * @param str the str
     * @return the result
     */
    public static Date format(String str, String pattern) throws ParseException {
        DateFormat df = new SimpleDateFormat(pattern);
        return df.parse(str);
    }

    /**
     *
     * <p>
     * DateHelper.format(new Date()) =
     * </p>
     *
     * @param zonedDateTime the zonedDateTime
     * @param pattern the pattern
     * @return the result
     */
    public static String format(ZonedDateTime zonedDateTime, String pattern) throws ParseException {
        return format(zonedDateTime, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     *
     * <p>
     * DateHelper.format(new Date()) =
     * </p>
     *
     * @param zonedDateTime the zonedDateTime
     * @param df the df
     * @return the result
     */
    public static String format(ZonedDateTime zonedDateTime, DateTimeFormatter df) throws ParseException {
        return zonedDateTime.format(df);
    }

    /**
     *
     * <br />                 yyyy-MM-dd HH:mm:ss
     * {@link #format(Date, String)}
     * <pre>
     *   DateHelper.format(new Date()) =           2020-05-23 17:06:30
     * </pre>
     *
     * @param date the date
     * @return the result
     * @see #format(Date, String)
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
     *
     * <br />                 yyyy-MM-dd HH:mm:ss
     * {@link #format(Date, String)}
     * <pre>
     *   DateHelper.format(new Date()) =           2020-05-23 17:06:30
     * </pre>
     *
     * @param date the date
     * @return the result
     * @see #format(Date, String)
     */
    public static String format(Date date) {
        return format(date, DateFormatConstant.YYYY_MM_DD_HH_MM_SS);
    }

    /**
     *
     * <p>
     * DateHelper.format(new Date(), "yyyy-MM-dd") =
     * </p>
     *
     * @param localDateTime the localDateTime
     * @param pattern the pattern
     * @return the result
     */
    public static String format(LocalDateTime localDateTime, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).format(localDateTime);
    }

    /**
     *
     * <p>
     * DateHelper.format(new Date(), "yyyy-MM-dd") =
     * </p>
     *
     * @param localTime the localTime
     * @param pattern the pattern
     * @return the result
     */
    public static String format(LocalTime localTime, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).format(localTime);
    }

    /**
     *
     * <p>
     * DateHelper.format(new Date(), "yyyy-MM-dd") =
     * </p>
     *
     * @param localDate the localDate
     * @param pattern the pattern
     * @return the result
     */
    public static String format(LocalDate localDate, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).format(localDate);
    }

    /**
     *
     * <p>
     * DateHelper.format(new Date(), "yyyy-MM-dd") =
     * </p>
     *
     * @param date the date
     * @param pattern the pattern
     * @return the result
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
     * @param date the date
     * @param dateFormat the dateFormat
     * @return the result
     */
    public static String format(Date date, DateFormat dateFormat) {
        if (null == date) {
            return null;
        }
        DateFormat df = null == dateFormat ? createDefaultDateFormat(DateFormatConstant.YYYY_MM_DD_HH_MM_SS) : dateFormat;
        return df.format(date);
    }

    /**
     *
     * <p>
     * DateHelper.format(1111) =
     * </p>
     *
     * @param time the time
     * @return the result
     */
    public static String format(long time) {
        return format(time, DateFormatConstant.YYYY_MM_DD_HH_MM_SS);
    }

    /**
     *
     * <p>
     * DateHelper.format(1111, "yyyy-MM-dd") =
     * </p>
     *
     * @param time the time
     * @param pattern the pattern
     * @return the result
     */
    public static String format(long time, String pattern) {
        DateFormat df = createDefaultDateFormat(pattern);
        return df.format(time);
    }

    /**
     *
     *
     * @param time the time
     * @param dateFormat the dateFormat
     * @return the result
     */
    public static String format(long time, DateFormat dateFormat) {
        DateFormat df = null == dateFormat ? createDefaultDateFormat(DateFormatConstant.YYYY_MM_DD_HH_MM_SS) : dateFormat;
        return df.format(time);
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
     * @param node                   year   ,"month","week","day","huor","minute","second"
     * @param num              +            -
     * @return the result
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
     * @param dateTimeFormatter the dateTimeFormatter
     * @return the result
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
     *                      {num}
     *
     * @param num the num
     * @return                      {num}
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
     *                    {@link #currentString()}
     *
     * @return the result
     */
    public static String getCurrentDate() {
        return currentString();
    }

    /**
     *                0
     *
     * @param rightNow the rightNow
     * @return the int
     */
    public static int getDelayToNextMinute(long rightNow) {
        return (int) (MILLISECONDS_PER_MINUTE - (rightNow % MILLISECONDS_PER_MINUTE));
    }

    /**
     *
     *
     * @param rightNow the rightNow
     * @return the result
     */
    public static long getPreMinuteMills(long rightNow) {
        return rightNow - (rightNow % MILLISECONDS_PER_MINUTE) - 1;
    }

    /**
     *
     *
     * @return the result
     */
    public static long getTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     *
     *
     * @return the result
     */
    public static long getTimeNanos() {
        return System.nanoTime();
    }

    /**
     *
     *
     * @param localTime the localTime
     * @param localTimeStirs the localTimeStirs
     * @return the result
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
     * @param localDateTime the localDateTime
     * @param localDateTimes the localDateTimes
     * @return the result
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
     *
     *
     * @param localTime the localTime
     * @param localTimeStirs the localTimeStirs
     * @return the result
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
     * @param localTime the localTime
     * @param localTimes the localTimes
     * @return the result
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
     * <p>                                                                        </ p>
     * <p>
     *
     *                                                    ParseException   </ p>
     *
     *
     * @param str                      null
     * @return the result
     * @throws IllegalArgumentException                                        null
     * @throws ParseException
     */
    public static Calendar parseCalendar(String str) throws ParseException {
        Calendar calendar = getInstance();
        calendar.setTime(parseDateWithLeniency(str, DATE_FORMATS, true));
        return calendar;
    }

    /**
     * <p>                                                                        </ p>
     * <p>
     *
     *                                                    ParseException   </ p>
     *
     *
     * @param str                                null
     * @param parsePatterns                                           SimpleDateFormat         null
     * @return the result
     * @throws IllegalArgumentException                                        null
     * @throws ParseException
     */
    public static Date parseDate(String str, String[] parsePatterns) throws ParseException {
        return parseDateWithLeniency(str, parsePatterns, true);
    }

    /**
     * <p>                                                                        </ p>
     * <p>
     *
     *                                                    ParseException   </ p>
     *
     *
     * @param str                      null
     * @return the result
     * @throws IllegalArgumentException                                        null
     * @throws ParseException
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
     * <p>                                                                        </ p>
     * <p>
     *
     *                                                    ParseException   </ p>
     *
     *
     * @param str                      null
     * @return the result
     * @throws IllegalArgumentException                                        null
     * @throws ParseException
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
     * <p>                                                                        </ p>
     * <p>
     *
     *                                                    ParseException   </ p>
     *
     *
     * @param str                      null
     * @return the result
     * @throws IllegalArgumentException                                        null
     * @throws ParseException
     */
    public static LocalDateTime parseLocalDateTimeSafe(String str)  {
        Date date = parseDateSafe(str);
        if (null == date) {
            return null;
        }
        return toLocalDateTime(date);
    }

    /**
     * <p>                                                                        </ p>
     * <p>
     *
     *                                                    ParseException   </ p>
     *
     *
     * @param str                      null
     * @return the result
     * @throws IllegalArgumentException                                        null
     * @throws ParseException
     */
    public static Date parseDate(String str, final String parsePattern) throws ParseException {
        return parseDateWithLeniency(str, new String[]{parsePattern}, true);
    }

    /**
     *          epochMilli         Date
     *
     * @param epochMilli the epochMilli
     * @return Date
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
     * LocalDate -> Date
     *
     * @param localDate localDate
     * @return Date
     */
    public static Date parseDate(final LocalDate localDate) {
        return parseDate(localDate, null);
    }

    /**
     * LocalDate -> Date
     *
     * @param localDate localDate
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
     * Instant -> Date
     *
     * @param instant instant
     * @return Date
     */
    public static Date parseDate(final Instant instant) {
        return Date.from(Optional.ofNullable(instant).orElse(Instant.now()));
    }

    /**
     * date -> Date
     *
     * @param date date
     * @return Date
     */
    public static Date parseDate(final java.sql.Date date) {
        return new Date(date.getTime());
    }

    /**
     * time -> Date
     *
     * @param time time
     * @return Date
     */
    public static Date parseDate(final Time time) {
        return toDate(time.toLocalTime());
    }

    /**
     *          Date
     *
     * @param calendar the calendar
     * @return Date
     */
    public static Date parseDate(final Calendar calendar) {
        return toDate(calendar.toInstant());
    }

    /**
     * LocalDate and LocalTime -> Date
     *
     * @param localDate localDate
     * @param localTime localTime
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
     * LocalDateTime -> Date
     *
     * @param localDateTime localTime
     * @return Date
     */
    public static Date parseDate(final LocalDateTime localDateTime) {
        return parseDate(localDateTime, null);
    }

    /**
     * LocalDateTime -> Date
     *
     * @param localDateTime localTime
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
     * <p>                                                                        </ p>
     * <p>
     *
     *                                           ParseException   </ p>
     *
     * @param str                                null
     * @param parsePatterns                                           SimpleDateFormat         null
     * @param loc the loc
     * @return Date
     * @throws IllegalArgumentException                                        null
     * @throws ParseException
     * @see Calendar
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
     *                                           ParseException   </ p>
     *
     * @param str                                null
     * @param parsePatterns                                           SimpleDateFormat         null
     * @param lenient             /
     * @return Date
     * @throws IllegalArgumentException                                        null
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
     *                                           ParseException   </ p>
     *
     * @param str                                null
     * @param parsePatterns                                           SimpleDateFormat         null
     * @param lenient             /
     * @param loc the loc
     * @return Date
     * @throws IllegalArgumentException                                        null
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
     * YearMonth   Date
     *       dayOfMonth         1   31                                                      2         29            28
     *                                                                   toDateEndOfMonth(YearMonth)
     *
     * @param yearMonth YearMonth
     * @return Date
     */
    public static Date parserDate(YearMonth yearMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth");
        return toDate(yearMonth.atDay(1));
    }

    /**
     * YearMonth   Date
     *       dayOfMonth         1   31                                                      2         29            28
     *                                                                   toDateEndOfMonth(YearMonth)
     *
     * @param yearMonth  YearMonth
     * @param dayOfMonth the dayOfMonth
     * @return Date
     */
    public static Date parserDate(YearMonth yearMonth, int dayOfMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth");
        return toDate(yearMonth.atDay(dayOfMonth));
    }

    /**
     * ZonedDateTime   Date
     *
     *
     * @param zonedDateTime ZonedDateTime
     * @return Date
     */
    public static Date parserDate(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return Date.from(zonedDateTime.toInstant());
    }

    /**
     * localTime   Date
     *
     * @param temporalAccessor temporalAccessor
     * @return Date
     * @see LocalDate
     * @see Date
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
     * localTime   Date
     *
     * @param localTime localeDate
     * @return Date
     * @see LocalDate
     * @see Date
     */
    public static Date toDate(LocalTime localTime) {
        if (null == localTime) {
            return null;
        }

        return toDate(Instant.ofEpochSecond(localTime.getSecond(), localTime.getNano()));
    }

    /**
     * localeDate   Date
     *
     * @param localDate localeDate
     * @return Date
     * @see LocalDate
     * @see Date
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
     * localeDate   Date
     *
     * @param localDateTime localDateTime
     * @return Date
     * @see LocalDate
     * @see Date
     */
    public static Date toDate(LocalDateTime localDateTime) {
        return toDate(localDateTime, DEFAULT_ZONE_ID);
    }

    /**
     * localeDateTime   Date
     *
     * @param localDateTime localDateTime
     * @param zoneId the zoneId
     * @return Date
     * @see LocalDate
     * @see Date
     */
    public static Date toDate(LocalDateTime localDateTime, ZoneId zoneId) {
        if (null == localDateTime) {
            return null;
        }
        return Date.from(localDateTime.atZone(Optional.ofNullable(zoneId).orElse(DEFAULT_ZONE_ID)).toInstant());
    }

    /**
     * localeDate   Date
     *
     * @param localDate localDate
     * @param zoneId the zoneId
     * @return Date
     * @see LocalDate
     * @see Date
     */
    public static Date toDate(LocalDate localDate, ZoneId zoneId) {
        if (null == localDate) {
            return null;
        }
        return Date.from(localDate.atStartOfDay().atZone(Optional.ofNullable(zoneId).orElse(DEFAULT_ZONE_ID)).toInstant());
    }

    /**
     * java.db.Date   Date
     *
     * @param date date
     * @return Date
     * @see LocalDate
     * @see Date
     * @see Instant
     */
    public static Date toDate(java.sql.Date date) {
        return date;
    }

    /**
     * java.db.Date   Date
     *
     * @param date date
     * @return Date
     * @see LocalDate
     * @see Date
     * @see Instant
     */
    public static Date toDate(Timestamp date) {
        return date;
    }

    /**
     * instant   Date
     *
     * @param instant instant
     * @return Date
     * @see LocalDate
     * @see Date
     * @see Instant
     */
    public static Date toDate(Instant instant) {
        if (null == instant) {
            return null;
        }
        return Date.from(instant);
    }

    /**
     * duration   Date
     *
     * @param duration time
     * @return Date
     * @see LocalDate
     * @see Date
     * @see Instant
     */
    public static Date toDate(Duration duration) {
        return new Date(System.currentTimeMillis() - duration.toMillis());
    }

    /**
     * long    Date
     *
     * @param time time
     * @return Date
     * @see LocalDate
     * @see Date
     * @see Instant
     */
    public static Date toDate(long time) {
        return Date.from(Instant.ofEpochMilli(complementMilliseconds(time)));
    }

    /**
     * Date
     *    1970-01-01T00:00:00Z
     *
     * @param date Date
     * @return the result
     */
    public static long toEpochMilli(Date date) {
        Objects.requireNonNull(date, "date");
        return date.getTime();
    }

    /**
     * Timestamp
     *    1970-01-01T00:00:00Z
     *
     * @param timestamp Timestamp
     * @return the result
     */
    public static long toEpochMilli(Timestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        return timestamp.getTime();
    }

    /**
     * LocalDateTime
     *    1970-01-01T00:00:00Z
     *
     * @param localDateTime LocalDateTime
     * @return the result
     */
    public static long toEpochMilli(LocalDateTime localDateTime) {
        return toInstant(localDateTime).toEpochMilli();
    }

    /**
     * LocalDate
     *    1970-01-01T00:00:00Z
     *
     * @param localDate LocalDate
     * @return the result
     */
    public static long toEpochMilli(LocalDate localDate) {
        return toInstant(localDate).toEpochMilli();
    }

    /**
     * Instant
     *    1970-01-01T00:00:00Z
     *
     * @param instant Instant
     * @return the result
     */
    public static long toEpochMilli(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return instant.toEpochMilli();
    }

    /**
     * ZonedDateTime                        zonedDateTime
     *    1970-01-01T00:00:00Z
     *
     * @param zonedDateTime ZonedDateTime
     * @return the result
     */
    public static long toEpochMilli(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toInstant().toEpochMilli();
    }

    /**
     * Date   Instant
     *
     * @param date Date
     * @return Instant
     */
    public static Instant toInstant(Date date) {
        Objects.requireNonNull(date, "date");
        return date.toInstant();
    }

    /**
     * Timestamp   Instant
     *
     * @param timestamp Timestamp
     * @return Instant
     */
    public static Instant toInstant(Timestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        return timestamp.toInstant();
    }

    /**
     * LocalDateTime   Instant
     *
     * @param localDateTime LocalDateTime
     * @return Instant
     */
    public static Instant toInstant(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return localDateTime.atZone(DEFAULT_ZONE_ID).toInstant();
    }

    /**
     * LocalDate   Instant
     *
     * @param localDate LocalDate
     * @return Instant
     */
    public static Instant toInstant(LocalDate localDate) {
        return toLocalDateTime(localDate).atZone(DEFAULT_ZONE_ID).toInstant();
    }

    /**
     * LocalTime   Instant
     *                   +LocalTime            LocalDateTime         Instant
     *
     * @param localTime LocalTime
     * @return Instant
     */
    public static Instant toInstant(LocalTime localTime) {
        return toLocalDateTime(localTime).atZone(DEFAULT_ZONE_ID).toInstant();
    }

    /**
     *          epochMilli         Instant
     *
     * @param epochMilli the epochMilli
     * @return Instant
     */
    public static Instant toInstant(long epochMilli) {
        Objects.requireNonNull(epochMilli, "epochMilli");
        return Instant.ofEpochMilli(epochMilli);
    }

    /**
     * temporal   Instant
     *
     * @param temporal TemporalAccessor
     * @return Instant
     */
    public static Instant toInstant(TemporalAccessor temporal) {
        return Instant.from(temporal);
    }

    /**
     * ZonedDateTime   Instant
     *          zonedDateTime
     *
     * @param zonedDateTime ZonedDateTime
     * @return Instant
     */
    public static Instant toInstant(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toInstant();
    }

    /**
     * Date   LocalDate
     *
     * @param date Date
     * @return LocalDate
     * @throws NullPointerException if date is not valid
     */
    public static LocalDate toLocalDate(final Date date) {
        return toLocalDateTime(date).toLocalDate();
    }

    /**
     * dateStr   LocalDate
     *
     * @param dateStr dateStr
     * @return LocalDate
     * @throws NullPointerException if date is not valid
     */
    public static LocalDate toLocalDate(final String dateStr) throws ParseException {
        return toLocalDateTime(dateStr).toLocalDate();
    }

    /**
     * Date   LocalDate
     *
     * @param calendar calendar
     * @return LocalDate
     * @throws NullPointerException if calendar is not valid
     */
    public static LocalDate toLocalDate(final Calendar calendar) {
        return toLocalDate(calendar.getTime());
    }
    /**
     * String   LocalDate
     *
     * @param str str
     * @param format format
     * @return LocalDate
     * @throws NullPointerException if localDateTime is not valid
     */
    public static LocalDate toLocalDate(String str, String format) {
        Objects.requireNonNull(str, "str");
        Objects.requireNonNull(format, "format");
        return toLocalDate(DateTimeFormatter.ofPattern(format).parse(str));
    }

    /**
     * LocalDateTime   LocalDate
     *
     * @param localDateTime LocalDateTime
     * @return LocalDate
     * @throws NullPointerException if localDateTime is not valid
     */
    public static LocalDate toLocalDate(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return localDateTime.toLocalDate();
    }

    /**
     * Instant   LocalDate
     *
     * @param instant Instant
     * @return LocalDate
     */
    public static LocalDate toLocalDate(Instant instant) {
        return toLocalDateTime(instant).toLocalDate();
    }

    /**
     *          epochMilli         LocalDate
     *
     * @param epochMilli the epochMilli
     * @return LocalDate
     */
    public static LocalDate toLocalDate(long epochMilli) {
        Objects.requireNonNull(epochMilli, "epochMilli");
        return toLocalDateTime(epochMilli).toLocalDate();
    }

    /**
     * temporal   LocalDate
     *
     * @param temporal TemporalAccessor
     * @return LocalDate
     */
    public static LocalDate toLocalDate(TemporalAccessor temporal) {
        return LocalDate.from(temporal);
    }

    /**
     * ZonedDateTime   LocalDate
     *
     *
     * @param zonedDateTime ZonedDateTime
     * @return LocalDate
     */
    public static LocalDate toLocalDate(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toLocalDate();
    }

    /**
     * YearMonth   LocalDate
     *       dayOfMonth         1   31                                                      2         29            28
     *                                                                   toLocalDateEndOfMonth(YearMonth)
     *
     * @param yearMonth  YearMonth
     * @param dayOfMonth the dayOfMonth
     * @return LocalDate
     */
    public static LocalDate toLocalDate(YearMonth yearMonth, int dayOfMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth");
        return yearMonth.atDay(dayOfMonth);
    }

    /**
     * YearMonth   LocalDate
     *
     * @param yearMonth YearMonth
     * @return LocalDate
     */
    public static LocalDate toLocalDateEndOfMonth(YearMonth yearMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth");
        return yearMonth.atEndOfMonth();
    }

    /**
     * YearMonth   LocalDate
     *
     * @param yearMonth YearMonth
     * @return LocalDate
     */
    public static LocalDate toLocalDateStartOfMonth(YearMonth yearMonth) {
        return toLocalDate(yearMonth, 1);
    }

    /**
     * Date -> LocalDateTime
     *
     * @param date date
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(final Date date) {
        return toLocalDateTime(date, DEFAULT_ZONE_ID);
    }

    /**
     * calendar -> LocalDateTime
     *
     * @param calendar the calendar
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(final Calendar calendar) {
        return toLocalDateTime(calendar.getTime());
    }
    /**
     * Duration -> LocalDateTime
     *
     * @param duration Duration
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(Duration duration) {
        return toLocalDateTime(toDate(duration));
    }
    /**
     * Date -> LocalDateTime
     *
     * @param date    date
     * @param pattern the pattern
     * @return LocalDateTime
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
     * Date -> LocalDateTime
     *
     * @param date date
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(final String date) throws ParseException {
        return toLocalDateTime(parseDate(date));
    }

    /**
     * Timestamp   LocalDateTime
     *
     * @param timestamp Timestamp
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        return timestamp.toLocalDateTime();
    }

    /**
     * LocalDate   LocalDateTime
     *
     * @param localDate LocalDate
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(LocalDate localDate) {
        Objects.requireNonNull(localDate, "localDate");
        return localDate.atStartOfDay();
    }

    /**
     * LocalDate   LocalDateTime
     *
     * @param localDate LocalDate
     * @param localTime the localTime
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(LocalDate localDate, LocalTime localTime) {
        Objects.requireNonNull(localDate, "localDate");
        return LocalDateTime.of(localDate, localTime);
    }

    /**
     * LocalTime   LocalDateTime
     *                   +LocalTime            LocalDateTime
     *
     * @param localTime LocalTime
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(LocalTime localTime) {
        Objects.requireNonNull(localTime, "localTime");
        return LocalDate.now().atTime(localTime);
    }

    /**
     * Instant   LocalDateTime
     *
     * @param instant Instant
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, DEFAULT_ZONE_ID);
    }

    /**
     *          epochMilli         LocalDateTime
     *
     * @param epochMilli the epochMilli
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(long epochMilli) {
        Objects.requireNonNull(epochMilli, "epochMilli");
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), DEFAULT_ZONE_ID);
    }

    /**
     * temporal   LocalDateTime
     *
     * @param temporal TemporalAccessor
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(TemporalAccessor temporal) {
        return LocalDateTime.from(temporal);
    }

    /**
     * ZonedDateTime   LocalDateTime
     *
     *
     * @param zonedDateTime ZonedDateTime
     * @return LocalDateTime
     */
    public static LocalDateTime toLocalDateTime(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toLocalDateTime();
    }

    /**
     * Date -> LocalDateTime
     *
     * @param date date
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
     * Date -> LocalDateTime
     *
     * @param date date
     * @param zone zone
     * @return LocalDateTime
     */
    public static LocalTime toLocalTime(final Date date, final ZoneId zone) {
        return toLocalDateTime(date, zone).toLocalTime();
    }

    /**
     * Calendar -> LocalDateTime
     *
     * @param calendar date
     * @return LocalDateTime
     * @throws NullPointerException if calendar is not valid
     */
    public static LocalTime toLocalTime(final Calendar calendar) {
        return toLocalDateTime(calendar.getTime()).toLocalTime();
    }

    /**
     * Duration -> LocalTime
     *
     * @param duration Duration
     * @return LocalTime
     */
    public static LocalTime toLocalTime(Duration duration) {
        return toLocalTime(toDate(duration));
    }

    /**
     * dateStr -> LocalDateTime
     *
     * @param dateStr dateStr
     * @return LocalDateTime
     * @throws NullPointerException if dateStr is not valid
     */
    public static LocalTime toLocalTime(final String dateStr) throws ParseException {
        if (NumberUtils.isNumber(dateStr)) {
            return LocalTime.ofSecondOfDay(Long.parseLong(dateStr));
        }
        return toLocalDateTime(dateStr).toLocalTime();
    }

    /**
     * Date   LocalTime
     *
     * @param date Date
     * @return LocalTime
     */
    public static LocalTime toLocalTime(Date date) {
        return toLocalDateTime(date).toLocalTime();
    }

    /**
     * long   LocalTime
     *
     * @param epochMilli the epochMilli
     * @return LocalTime
     */
    public static LocalTime toLocalTime(long epochMilli) {
        return toLocalTime(Instant.ofEpochMilli(epochMilli));
    }

    /**
     * LocalDateTime   LocalTime
     *
     * @param localDateTime LocalDateTime
     * @return LocalTime
     */
    public static LocalTime toLocalTime(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return localDateTime.toLocalTime();
    }

    /**
     * Instant   LocalTime
     *
     * @param instant Instant
     * @return LocalTime
     */
    public static LocalTime toLocalTime(Instant instant) {
        return toLocalDateTime(instant).toLocalTime();
    }

    /**
     * temporal   LocalTime
     *
     * @param temporal TemporalAccessor
     * @return LocalTime
     */
    public static LocalTime toLocalTime(TemporalAccessor temporal) {
        return LocalTime.from(temporal);
    }

    /**
     * ZonedDateTime   LocalTime
     *
     *
     * @param zonedDateTime ZonedDateTime
     * @return LocalTime
     */
    public static LocalTime toLocalTime(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return zonedDateTime.toLocalTime();
    }
    /**
     * Date   Time
     *
     * @param date Date
     * @return Timestamp
     */
    public static Time toTime(Date date) {
        Objects.requireNonNull(date, "date");
        return new Time(date.getTime());
    }
    /**
     * Date   Time
     *
     * @param localTime Date
     * @return Timestamp
     */
    public static Time toTime(LocalTime localTime) {
        Objects.requireNonNull(localTime, "date");
        return new Time(toDate(localTime).getTime());
    }
    /**
     * Date   Timestamp
     *
     * @param date Date
     * @return Timestamp
     */
    public static Timestamp toTimestamp(Date date) {
        Objects.requireNonNull(date, "date");
        return new Timestamp(date.getTime());
    }

    /**
     * LocalDateTime   Timestamp
     *
     * @param localDateTime LocalDateTime
     * @return Timestamp
     */
    public static Timestamp toTimestamp(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return Timestamp.valueOf(localDateTime);
    }

    /**
     * Instant   Timestamp
     *
     * @param instant Instant
     * @return Timestamp
     */
    public static Timestamp toTimestamp(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return Timestamp.from(instant);
    }

    /**
     *          epochMilli   Timestamp
     *
     * @param epochMilli the epochMilli
     * @return Timestamp
     */
    public static Timestamp toTimestamp(long epochMilli) {
        return new Timestamp(epochMilli);
    }

    /**
     * Date   YearMonth
     *
     * @param date Date
     * @return YearMonth
     */
    public static YearMonth toYearMonth(Date date) {
        LocalDate localDate = toLocalDate(date);
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }

    /**
     * LocalDateTime   YearMonth
     *
     * @param localDateTime LocalDateTime
     * @return YearMonth
     */
    public static YearMonth toYearMonth(LocalDateTime localDateTime) {
        LocalDate localDate = toLocalDate(localDateTime);
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }

    /**
     * LocalDate   YearMonth
     *
     * @param localDate LocalDate
     * @return YearMonth
     */
    public static YearMonth toYearMonth(LocalDate localDate) {
        Objects.requireNonNull(localDate, "localDate");
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }

    /**
     * Instant   YearMonth
     *
     * @param instant Instant
     * @return YearMonth
     */
    public static YearMonth toYearMonth(Instant instant) {
        LocalDate localDate = toLocalDate(instant);
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }

    /**
     * ZonedDateTime   YearMonth
     *
     * @param zonedDateTime ZonedDateTime
     * @return YearMonth
     */
    public static YearMonth toYearMonth(ZonedDateTime zonedDateTime) {
        LocalDate localDate = toLocalDate(zonedDateTime);
        return YearMonth.of(localDate.getYear(), localDate.getMonthValue());
    }

    /**
     * Date   ZonedDateTime
     *
     * @param date Date
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(Date date) {
        Objects.requireNonNull(date, "date");
        return Instant.ofEpochMilli(date.getTime()).atZone(DEFAULT_ZONE_ID);
    }

    /**
     * Date   ZonedDateTime
     *
     * @param date   Date
     * @param zoneId the zoneId
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(Date date, String zoneId) {
        Objects.requireNonNull(zoneId, "zoneId");
        return toZonedDateTime(date, ZoneId.of(zoneId));
    }

    /**
     * Date   ZonedDateTime
     *
     * @param date Date
     * @param zone the zone
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(Date date, ZoneId zone) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(zone, "zone");
        return Instant.ofEpochMilli(date.getTime()).atZone(zone);
    }

    /**
     * LocalDateTime   ZonedDateTime
     *
     * @param localDateTime LocalDateTime
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        return localDateTime.atZone(DEFAULT_ZONE_ID);
    }

    /**
     * LocalDateTime   ZonedDateTime            zoneId
     *                      localDateTime   zoneId
     *
     * @param localDateTime LocalDateTime
     * @param zoneId        LocalDateTime
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(LocalDateTime localDateTime, String zoneId) {
        Objects.requireNonNull(localDateTime, "localDateTime");
        Objects.requireNonNull(zoneId, "zoneId");
        return localDateTime.atZone(ZoneId.of(zoneId));
    }

    /**
     * LocalDate   ZonedDateTime
     *
     * @param localDate LocalDate
     * @return ZonedDateTime such as 2020-02-19T00:00+08:00[Asia/Shanghai]
     */
    public static ZonedDateTime toZonedDateTime(LocalDate localDate) {
        Objects.requireNonNull(localDate, "localDate");
        return localDate.atStartOfDay().atZone(DEFAULT_ZONE_ID);
    }

    /**
     * LocalTime   ZonedDateTime
     *                   +LocalTime            ZonedDateTime
     *
     * @param localTime LocalTime
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(LocalTime localTime) {
        Objects.requireNonNull(localTime, "localTime");
        return LocalDate.now().atTime(localTime).atZone(DEFAULT_ZONE_ID);
    }

    /**
     * Instant   ZonedDateTime
     *
     * @param instant Instant
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, DEFAULT_ZONE_ID).atZone(DEFAULT_ZONE_ID);
    }

    /**
     *          epochMilli         ZonedDateTime
     *
     * @param epochMilli the epochMilli
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(long epochMilli) {
        Objects.requireNonNull(epochMilli, "epochMilli");
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), DEFAULT_ZONE_ID)
                .atZone(DEFAULT_ZONE_ID);
    }

    /**
     * temporal   ZonedDateTime
     *
     * @param temporal TemporalAccessor
     * @return ZonedDateTime
     */
    public static ZonedDateTime toZonedDateTime(TemporalAccessor temporal) {
        return LocalDateTime.from(temporal).atZone(DEFAULT_ZONE_ID);
    }

    /**
     *
     *
     * @param localTime the localTime
     * @return the result
     */
    private static LocalDateTime toDay(LocalTime localTime) {
        return LocalDateTime.of(LocalDate.now(), localTime);
    }

    /**
     *
     *
     * @param negate the negate
     * @param daysAsSecs the daysAsSecs
     * @param hoursAsSecs the hoursAsSecs
     * @param minsAsSecs the minsAsSecs
     * @param secs the secs
     * @param nanos the nanos
     * @return the result
     */
    private static long createSeconds(boolean negate, long daysAsSecs, long hoursAsSecs, long minsAsSecs, long secs, int nanos) {
        long exact = Math.addExact(daysAsSecs, Math.addExact(hoursAsSecs, Math.addExact(minsAsSecs, secs)));
        return negate ? -1 * exact : exact;
    }

    /**
     *
     *
     * @param text the text
     * @param parsed the parsed
     * @param multiplier the multiplier
     * @param errorText the errorText
     * @return the result
     */
    private static long parseNumber(CharSequence text, String parsed, int multiplier, String errorText) {
        // regex limits to [-+]?[0-9]+
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
     * Index of sign charaters (i.e. '+' or '-').
     *
     * @param str the str
     * @param startPos the startPos
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
     * @param signIdx the signIdx
     * @return the result
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
     *        DateFormat
     *
     * @param pattern the pattern
     * @return DateFormat
     * @see DateFormat
     */
    private static DateFormat createDefaultDateFormat(String pattern) {
        return createDateFormat(pattern, null);
    }

    /**
     *        DateFormat
     *
     * @param pattern the pattern
     * @param timeZone the timeZone
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
     * @param time the time
     * @return the result
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
     *
     *
     * @param year the year
     * @param month the month
     * @param day the day
     * @param hour the hour
     * @param minute the minute
     * @param second the second
     * @param milli the milli
     * @return the result
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
     * @return the result
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
     * @return the result
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
     * @return the result
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
     * @return the result
     */
    public static Calendar toCalendar(int year, int month, int day) {
        return toCalendar(year, month, day, 0, 0, 0);
    }

    /**
     *
     *
     * @param year the year
     * @param month the month
     * @return the result
     */
    public static Calendar toCalendar(int year, int month) {
        return toCalendar(year, month, 1, 0, 0, 0);
    }

    /**
     *
     *
     * @param date the date
     * @return the result
     */
    public static Calendar toCalendar(Date date) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(DEFAULT_ZONE_ID));
        c.setTime(date);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    /**
     *
     * <pre>
     *     trimAllWhitespace("test") = "test"
     *     trimAllWhitespace("test ") = "test"
     *     trimAllWhitespace(" test ") = "test"
     *     trimAllWhitespace(" te st ") = "test"
     *     trimAllWhitespace(null) = null
     * </pre>
     *
     * @param source the source
     * @return the result
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
     * @param searchChar the searchChar
     * @param start                                 0      0
     * @return the result
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
     * @param searchChar the searchChar
     * @param start                                 0      0
     * @param end                                   str.length()
     * @return the result
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
     * @return the result
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
     * @return the result
     */
    public static boolean isLeapYear(int year) {
        return Year.isLeap(year);
    }

    /**
     * @return the result
     */
    public static Date currentDate() {
        return new Date();
    }

    /**
     * @return the result
     */
    public static int thisYear() {
        return year(currentDate());
    }

    /**
     *
     *
     * @param date the date
     * @return the result
     */
    public static int year(Date date) {
        Instant instant = date.toInstant();
        ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());
        return zonedDateTime.getYear();
    }

    /**
     *        formatter          ZonedDateTime
     *
     * @param text the text
     * @param formatter DateTimeFormatter
     * @return ZonedDateTime
     */
    public static ZonedDateTime parseToZonedDateTime(String text, DateTimeFormatter formatter) {
        return ZonedDateTime.parse(text, formatter);
    }



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
     *
     * [CH] 2025-01-01 v1.0.0
     *
     * @param date                 Date   LocalDate   LocalDateTime
     * @param days the days
     * @param <T> the <T>
     * @return the result
     */
@SuppressWarnings("unchecked")
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
     *
     * [CH] 2025-01-01 v1.0.0
     *
     * @param date                  Date   LocalDateTime
     * @param hours the hours
     * @param <T> the <T>
     * @return the result
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
     *
     * [CH] 2025-01-01 v1.0.0
     *
     * @param date                    Date   LocalDateTime   LocalTime
     * @param minutes the minutes
     * @param <T> the <T>
     * @return the result
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
     *
     * [CH] 2025-01-01 v1.0.0
     *
     * @param date                                        Date/LocalDate/LocalDateTime/LocalTime
     * @param startDate                                      Date/LocalDate/LocalDateTime/LocalTime
     * @param endDate                                        Date/LocalDate/LocalDateTime/LocalTime
     * @return true                                   false
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
     * @return 本地时间，解析失败或参数为 null 时返回 {@code null}
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
     * @return 本地日期，解析失败或参数为 null 时返回 {@code null}
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