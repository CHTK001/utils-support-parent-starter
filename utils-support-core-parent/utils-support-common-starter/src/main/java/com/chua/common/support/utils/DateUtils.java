// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.utils;
import com.chua.common.support.collection.wrap.Wraps;
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
 * <p>[P3C-1.2 B2 豁免] 本类为公共日期工具 API，需保持对 {@link java.text.DateFormat} 参数和返回类型的支持
 * （下游自行传入 {@code SimpleDateFormat} 或 {@code DateTimeFormatter}），因此本地以
 * {@code SimpleDateFormat} 实现模式转换/时区/容错解析。</p>
 *
 * @author CH
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
    /**
     * One_day
     */
    public static final int ONE_DAY = 24 * 60 * 60;
    /**
     * One_hour
     */
    public static final int ONE_HOUR = 60;
    /**
     * One_minute
     */
    public static final int ONE_MINUTE = ONE_HOUR;
    /**
     * 准确_hours
     */
    public static final int ACCURACY_HOURS = 4;
    /**
     * 准确_minutes
     */
    public static final int ACCURACY_MINUTES = 5;
    /**
     * 准确_seconds
     */
    public static final int ACCURACY_SECONDS = 6;
    /**
     * 准确_milliseconds
     */
    public static final int ACCURACY_MILLISECONDS = 7;
    /**
     * 准确_milliseconds_forced
     */
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
    /**
     * Week
     */
    public static final String WEEK = "week";
    /**
     * Week_day
     */
    public static final int WEEK_DAY = 7;
    /**
     * 默认_zone_标识
     */
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
    /**
     * Month
     */
    private static final String MONTH = "month";
    /**
     * DAY
     */
    private static final String DAY = "day";
    /**
     * Hour
     */
    private static final String HOUR = "hour";
    /**
     * Minute
     */
    private static final String MINUTE = "minute";
    /**
     * Second
     */
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
    /**
     * 模式
     */
    private static final Pattern PATTERN =
            Pattern.compile("([-+]?)P(?:([-+]?[0-9]+)D)?" +
                            "(T(?:([-+]?[0-9]+)H)?(?:([-+]?[0-9]+)M)?(?:([-+]?[0-9]+)(?:[.,]([0-9]{0,9}))?S)?)?",
                    Pattern.CASE_INSENSITIVE);
    /**
     * 日期_格式化
     */
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
    /**
     * 索引_not_found
     */
    private static final int INDEX_NOT_FOUND = -1;
    /**
     * 获取指定日期的星期（0=周日，1=周一，...，6=周六）。
     *
     * @param date 基准日期
     * @return 星期的整数表示
     */
    public static int getWeek(Date date) {
        c.setTime(date);
        return c.get(DAY_OF_WEEK) - 1;
    }
    public static Date format(String str, String pattern) throws ParseException {
        // [P3C-1.2 B2] 公共 API，必须保持 String→Date 动态模式解析，Per-Call 新建
        DateFormat df = new SimpleDateFormat(pattern);
        return df.parse(str);
    }
    */
    public static String format(ZonedDateTime zonedDateTime, String pattern) throws ParseException {
        return format(zonedDateTime, DateTimeFormatter.ofPattern(pattern));
    }
     * @return 格式化后的字符串
     * @throws ParseException 异常
    */
    public static String format(ZonedDateTime zonedDateTime, DateTimeFormatter df) throws ParseException {
        return zonedDateTime.format(df);
    }
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
     *
     * @param localDateTime the 本地日期时间
     * @param pattern the 模式
     * @return the 结果
     */
    public static String format(LocalDateTime localDateTime, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).format(localDateTime);
    }
     * @return the 结果
     */
    public static String format(LocalTime localTime, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).format(localTime);
    }
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
     * 将毫秒时间戳格式化为默认格式的日期字符串（yyyy-MM-dd HH:mm:ss）。
     *
     * @param time 毫秒时间戳
     * @return 格式化后的日期字符串
     */
    public static String format(long time) {
        return format(time, DateFormatConstant.YYYY_MM_DD_HH_MM_SS);
    }
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
        DateFormat df = null == dateFormat
                ? createDefaultDateFormat(DateFormatConstant.YYYY_MM_DD_HH_MM_SS)
                : dateFormat;
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
     * @param localDateTimes the 本地日期时间
     * @return the 结果
     */
    public static LocalDateTime nearLocalDateTime(LocalDateTime localDateTime, LocalDateTime... localDateTimes) {
        Map<Long, LocalDateTime> result = Wraps.map(localDateTimes.length);
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
        return minLocalTime;
    }
    /**
     * 取就近的本地时间。
     *
     *
     * @param localTime the 本地时间
     * @param localTimes the 本地时间
     * @return the 结果
     */
    public static LocalTime nearLocalTime(LocalTime localTime, LocalTime... localTimes) {
        Map<Long, LocalTime> result = Wraps.map(localTimes.length);
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
     * @param str 日期时间字符串
     * @return 解析后的 日期，可能为 空
     * @throws IllegalArgumentException 若 str 为 空 或空字符串
     * @throws ParseException 异常
    */
    public static Date parseDate(String str) throws ParseException {
        if (StringUtils.isNullOrEmpty(str)) {
            throw new IllegalArgumentException("str is null");
        }
        if (str.length() > NUMBER_10 && NumberUtils.isNumber(str)) {
            return new Date(Long.parseLong(str));
        }
        Date date = null;
        try {
        } catch (ParseException e) {
            log.debug("[date] 解析日期失败，已忽略: {}", e.getMessage());
        }
        return null == date ? parseDateWithLeniency(str, DATE_FORMATS, Locale.US) : date;
    }
     * @return 解析后的 本地日期时间，解析失败返回 空
     */
    public static LocalDateTime parseLocalDateTimeSafe(String str)  {
        Date date = parseDateSafe(str);
        if (null == date) {
            return null;
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
    public static Date parseDateWithLeniency(String str, String[] parsePatterns,
                                             boolean lenient) throws ParseException {
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
    public static Date parseDateWithLeniency(String str, String[] parsePatterns, boolean lenient,
                                             Locale loc) throws ParseException {
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
                        throw new DateTimeParseException("Text cannot be parsed to a Duration: overflow", str, 0, ex);
                    }
                }
            }
        }
        throw new ParseException("Unable to parse the date: " + str, -1);
    }
     */
    public static Date parserDate(YearMonth yearMonth, int dayOfMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth");
        return toDate(yearMonth.atDay(dayOfMonth));
    }
     * @param zonedDateTime zoned日期时间
     * @return Date
     */
    public static Date parserDate(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "zonedDateTime");
        return Date.from(zonedDateTime.toInstant());
    }
     *
     * @param temporalAccessor 时间访问器
     * @return 对应的 日期，不支持的类型返回 空
     */
    public static Date toDate(TemporalAccessor temporalAccessor) {
        if (null == temporalAccessor) {
        }
        if (temporalAccessor instanceof LocalDateTime) {
            return toDate((LocalDateTime)temporalAccessor);
        }
        if (temporalAccessor instanceof LocalDate) {
            return toDate((LocalDate)temporalAccessor);
        }
    }
    /**
     * 本地时间 转为 日期（取当天对应时刻，日期部分为 轮次）。
     *
     * @param localTime 时间
     * @return 对应的 日期
     */
    public static Date toDate(LocalTime localTime) {
        if (null == localTime) {
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
     *
     * @param localDate 日期
     * @param zoneId    时区，为 空 时使用系统默认时区
     * @return 对应的 日期
     */
    public static Date toDate(LocalDate localDate, ZoneId zoneId) {
        if (null == localDate) {
            return null;
        }
        return Date.from(localDate.atStartOfDay()
                .atZone(Optional.ofNullable(zoneId).orElse(DEFAULT_ZONE_ID))
                .toInstant());
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
     * @param dateStr 日期str
     * @return LocalDate
     * @throws NullPointerException if 日期 是否 not valid
     * @throws ParseException 异常
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
            return toLocalDateTime(parseDate(date,
                    Optional.ofNullable(pattern).orElse(DateFormatConstant.YYYY_MM_DD_HH_MM_SS)));
        } catch (ParseException e) {
            log.error("", e);
        }
        return null;
    }
    /**
     * 日期 -> 本地日期时间
     * @param date 日期
     * @return LocalDateTime
     * @throws ParseException 异常
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
        if (date instanceof java.sql.Date) {
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
     * @param dateStr 日期str
     * @return LocalDateTime
     * @throws NullPointerException if 日期str 是否 not valid
     * @throws ParseException 异常
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
     * 转换为Day。
     *
     *
     * @param localTime the 本地时间
     * @return the 结果
     */
    private static LocalDateTime toDay(LocalTime localTime) {
        return LocalDateTime.of(LocalDate.now(), localTime);
    }
    /**
     * 生成秒级时间戳。
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
    private static long createSeconds(boolean negate, long daysAsSecs, long hoursAsSecs, long minsAsSecs, long secs,
                                      int nanos) {
        long exact = Math.addExact(daysAsSecs, Math.addExact(hoursAsSecs, Math.addExact(minsAsSecs, secs)));
        return negate ? -1 * exact : exact;
    }
    /**
     * 解析 Number。
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
    /**
     * 索引 的 标志 charaters (i.e. '+' 或 '-').
     *
     * @param str the str
     * @param startPos the 启动采购订单
     * @return                                                             -1
     */
    private static int indexOfSignChars(String str, int startPos) {
        if (idx < 0) {
            idx = indexOf(str, '-', startPos);
        }
        if (idx < 0) {
            idx = indexOf(str, '/', startPos);
        }
        return idx;
    }
    /**
     * 转换时区表示。
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
     *
     * <p>[P3C-1.2 B2 豁免] 必须返回 {@link DateFormat} 抽象类型，否则 {@link #createDateFormat(String, String)} 的
     * 返回类型与 {@code format(Date, DateFormat)} 参数类型无法匹配；实现内部需要 {@code setLenient/setTimeZone}
     * 等不支持的动态 API，故使用 {@code SimpleDateFormat}。</p>
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
     * 补齐毫秒位。
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
     * 转换为Calendar。
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
     * 转换为Calendar。
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
     * 转换为Calendar。
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
     * 转换为Calendar。
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
     * 转换为Calendar。
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
     * 转换为Calendar。
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
     * 查找元素下标。
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
     * 查找元素下标。
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
     * 时间格式化器（ISO："HH:mm:ss"）
     */
    private static final DateTimeFormatter PARSE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    /**
     * 日期格式化器（ISO："yyyy-MM-dd"）
     */
    private static final DateTimeFormatter PARSE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
}