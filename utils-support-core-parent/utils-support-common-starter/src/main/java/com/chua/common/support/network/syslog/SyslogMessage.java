package com.chua.common.support.network.syslog;

import lombok.Builder;
import lombok.Getter;

import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.utils.CollectionUtils;

/**
 * Syslog 消息模型，支持 RFC 5424（IETF 新格式）与 RFC 3164（BSD 旧格式）的编码与解析。
 *
 * <p>PRI = facility * 8 + severity。编码示例：</p>
 * <ul>
 *   <li>RFC 5424：{@code <34>1 2026-11-23T06:15:30.123Z web01 java 1234 - - Boot completed}</li>
 *   <li>RFC 3164：{@code <34>Nov 23 14:15:30 web01 java: Boot completed}</li>
 * </ul>
 *
 * <p>解析时自动识别格式：RFC 5424 以 {@code <PRI>1 } 开头；否则尝试 RFC 3164；
 * 两者都不匹配时按 user.info 兜底保留原文，保证不丢日志。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Getter
@Builder
public class SyslogMessage {

    /**
     * RFC 5424 协议版本号。
     */
    public static final int RFC5424_VERSION = 1;

    /**
     * 默认应用名。
     */
    public static final String DEFAULT_APP_NAME = "java";

    /**
     * PRI 最大合法值：facility(23) * 8 + severity(7)。
     */
    private static final int MAX_PRI = 191;

    /**
     * 兜底 PRI：user.info = 1 * 8 + 6 = 14。
     */
    private static final int DEFAULT_PRI = 14;

    /**
     * RFC 3164 英文月份缩写（下标 = 月份数值 - 1）。
     */
    private static final String[] MONTH_ABBREVIATIONS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    /**
     * RFC 5424 时间戳格式：ISO 8601 UTC，精确到毫秒。
     */
    private static final DateTimeFormatter RFC5424_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * RFC 5424 报文结构：PRI、版本、时间戳、主机名、应用名、进程 ID、消息 ID、其余部分（SD + MSG）。
     */
    private static final Pattern RFC5424_PATTERN = Pattern.compile(
            "^<(\\d{1,3})>(\\d+) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+)( (.*))?$", Pattern.DOTALL);

    /**
     * RFC 3164 报文结构：PRI、月、日（可空格补齐）、时间、主机名、TAG、正文。
     */
    private static final Pattern RFC3164_PATTERN = Pattern.compile(
            "^<(\\d{1,3})>([A-Za-z]{3})\\s+(\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2}) (\\S+) ([^:\\s]+)[:,]?\\s?(.*)$",
            Pattern.DOTALL);

    /**
     * 设施（0-23），见 {@link SyslogFacility}；缺省为 USER(1)。
     */
    @Builder.Default
    private final int facility = SyslogFacility.USER.getValue();

    /**
     * 严重级别（0-7），见 {@link SyslogSeverity}；缺省为 INFO(6)。
     */
    @Builder.Default
    private final int severity = SyslogSeverity.INFO.getValue();

    /**
     * 产生时间。
     */
    @Builder.Default
    private final Instant timestamp = Instant.now();

    /**
     * 主机名。
     */
    @Builder.Default
    private final String hostname = defaultHostname();

    /**
     * 应用名（RFC 5424 APP-NAME / RFC 3164 TAG）；缺省为 null（未设置），由发送端（如 {@link SyslogClient}）按其默认应用名补齐。
     */
    private final String appName;

    /**
     * 进程 ID（RFC 5424 PROCID，RFC 3164 不使用）。
     */
    private final String procId;

    /**
     * 消息 ID（RFC 5424 MSGID，RFC 3164 不使用）。
     */
    private final String msgId;

    /**
     * 结构化数据（RFC 5424 SD）：elementId -> (paramName -> paramValue)，按插入顺序输出。
     */
    @Builder.Default
    private final Map<String, Map<String, String>> structuredData = new LinkedHashMap<>();

    /**
     * 正文。
     */
    private final String message;

    /**
     * 按指定格式编码为单行报文（不含行尾换行）。
     *
     * @param format 目标格式，null 时按 RFC 5424
     * @return 单行 Syslog 报文
     */
    public String format(SyslogFormat format) {
        int pri = pri();
        if (format == SyslogFormat.RFC3164) {
            return formatRfc3164(pri);
        }
        return formatRfc5424(pri);
    }

    /**
     * 计算合法 PRI 值：facility/severity 越界时回落到默认值。
     *
     * @return 0-191
     */
    private int pri() {
        int facilityValue = (facility < 0 || facility > 23) ? SyslogFacility.USER.getValue() : facility;
        int severityValue = (severity < 0 || severity > 7) ? SyslogSeverity.INFO.getValue() : severity;
        int value = facilityValue * 8 + severityValue;
        return Math.min(value, MAX_PRI);
    }

    /**
     * 编码为 RFC 5424 报文。
     *
     * @param pri 已计算的 PRI
     * @return 报文
     */
    private String formatRfc5424(int pri) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('<').append(pri).append('>').append(RFC5424_VERSION).append(' ');
        sb.append(RFC5424_TIMESTAMP.format(timestamp == null ? Instant.now() : timestamp)).append(' ');
        sb.append(nilIfEmpty(hostname)).append(' ');
        sb.append(nilIfEmpty(appName)).append(' ');
        sb.append(nilIfEmpty(procId)).append(' ');
        sb.append(nilIfEmpty(msgId)).append(' ');
        sb.append(formatStructuredData());
        if (StringUtils.isNotEmpty(message)) {
            sb.append(' ').append(message);
        }
        return sb.toString();
    }

    /**
     * 编码结构化数据段：空数据为 {@code -}，否则为连续的 {@code [id k="v"]}。
     *
     * @return SD 段
     */
    private String formatStructuredData() {
        if (CollectionUtils.isEmpty(structuredData)) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(64);
        for (Map.Entry<String, Map<String, String>> element : structuredData.entrySet()) {
            sb.append('[').append(escapeSd(element.getKey()));
            if (element.getValue() != null) {
                for (Map.Entry<String, String> param : element.getValue().entrySet()) {
                    sb.append(' ').append(escapeSd(param.getKey()))
                            .append("=\"").append(escapeSd(param.getValue())).append('"');
                }
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /**
     * 编码为 RFC 3164 报文（本地时区时间）。
     *
     * @param pri 已计算的 PRI
     * @return 报文
     */
    private String formatRfc3164(int pri) {
        Instant instant = timestamp == null ? Instant.now() : timestamp;
        LocalDateTime local = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder(96);
        sb.append('<').append(pri).append('>');
        sb.append(MONTH_ABBREVIATIONS[local.getMonthValue() - 1]).append(' ');
        sb.append(String.format("%2d", local.getDayOfMonth())).append(' ');
        sb.append(String.format("%02d:%02d:%02d", local.getHour(), local.getMinute(), local.getSecond())).append(' ');
        sb.append(nilIfEmpty(hostname)).append(' ');
        sb.append(nilIfEmpty(appName)).append(": ");
        sb.append(message == null ? "" : message);
        return sb.toString();
    }

    /**
     * 解析一行 Syslog 报文，自动识别 RFC 5424 / RFC 3164；均不匹配时按 user.info 兜底保留原文。
     *
     * @param raw 原始报文（可含尾部换行），可为 null
     * @return 消息对象，不为 null
     */
    public static SyslogMessage parse(String raw) {
        String line = normalize(raw);
        if (line.isEmpty()) {
            return fallback("");
        }
        Matcher matcher = RFC5424_PATTERN.matcher(line);
        if (matcher.matches() && Integer.toString(RFC5424_VERSION).equals(matcher.group(2))) {
            return parseRfc5424(matcher);
        }
        matcher = RFC3164_PATTERN.matcher(line);
        if (matcher.matches()) {
            return parseRfc3164(matcher);
        }
        return fallback(line);
    }

    /**
     * 规范化原始报文：去 BOM、去首尾空白与尾部换行、截掉 PRI 前的杂散前缀。
     *
     * @param raw 原始报文
     * @return 规范化后的行，不为 null
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String line = raw.strip();
        if (line.startsWith("\uFEFF")) {
            line = line.substring(1);
        }
        int priStart = line.indexOf('<');
        if (priStart > 0) {
            line = line.substring(priStart);
        }
        return line;
    }

    /**
     * 解析 RFC 5424 报文。
     *
     * @param matcher 已匹配的 RFC 5424 正则
     * @return 消息对象
     */
    private static SyslogMessage parseRfc5424(Matcher matcher) {
        int pri = parsePri(matcher.group(1));
        SyslogMessageBuilder builder = builder()
                .facility(pri / 8)
                .severity(pri % 8)
                .timestamp(parseIsoTimestamp(matcher.group(3)))
                .hostname(dashToNull(matcher.group(4)))
                .appName(dashToNull(matcher.group(5)))
                .procId(dashToNull(matcher.group(6)))
                .msgId(dashToNull(matcher.group(7)));
        String rest = matcher.group(9) == null ? "" : matcher.group(9);
        applyStructuredDataAndMessage(builder, rest);
        return builder.build();
    }

    /**
     * 拆分 RFC 5424 报文的 SD 段与 MSG 段。
     *
     * @param builder 消息构造器
     * @param rest    MSGID 之后的剩余部分
     */
    private static void applyStructuredDataAndMessage(SyslogMessageBuilder builder, String rest) {
        if (rest.isEmpty()) {
            builder.message("");
            return;
        }
        if (rest.charAt(0) == '[') {
            int end = findStructuredDataEnd(rest);
            builder.structuredData(parseStructuredData(rest.substring(0, end)));
            String remainder = rest.substring(end);
            if (remainder.startsWith(" ")) {
                remainder = remainder.substring(1);
            }
            builder.message(remainder);
            return;
        }
        if (rest.charAt(0) == '-') {
            String remainder = rest.substring(1);
            if (remainder.startsWith(" ")) {
                remainder = remainder.substring(1);
            }
            builder.message(remainder);
            return;
        }
        builder.message(rest);
    }

    /**
     * 定位 SD 段结束位置（最后一个未转义 ']' 的下一位）。
     *
     * @param rest 剩余报文，以 '[' 开头
     * @return SD 段结束索引（不含）
     */
    private static int findStructuredDataEnd(String rest) {
        for (int i = 1; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\\' && i + 1 < rest.length()) {
                i++;
                continue;
            }
            if (c == ']') {
                return i + 1;
            }
        }
        return rest.length();
    }

    /**
     * 解析 SD 段：{@code [id k="v" k2="v2"][id2 ...]}。
     *
     * @param sdPart SD 段文本
     * @return 结构化数据（保持顺序），解析失败的部分被跳过
     */
    private static Map<String, Map<String, String>> parseStructuredData(String sdPart) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        int i = 0;
        while (i < sdPart.length()) {
            if (sdPart.charAt(i) != '[') {
                i++;
                continue;
            }
            int end = findStructuredDataEnd(sdPart.substring(i));
            String elementBody = sdPart.substring(i + 1, i + end - 1);
            parseStructuredDataElement(elementBody, result);
            i += end;
        }
        return result;
    }

    /**
     * 解析单个 SD element：首 token 为 elementId，其余为 {@code k="v"}。
     *
     * @param body   element 体（不含方括号）
     * @param result 解析结果容器
     */
    private static void parseStructuredDataElement(String body, Map<String, Map<String, String>> result) {
        List<String> tokens = tokenizeSdElement(body);
        if (tokens.isEmpty()) {
            return;
        }
        String elementId = unescapeSd(tokens.get(0));
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = unescapeSd(token.substring(0, eq));
            String value = unescapeSd(stripSdQuotes(token.substring(eq + 1)));
            params.put(name, value);
        }
        result.put(elementId, params);
    }

    /**
     * 按 SD 词法切分 element 体：引号内空格不切分，反斜杠转义不切分。
     *
     * @param body element 体
     * @return token 列表
     */
    private static List<String> tokenizeSdElement(String body) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                current.append(c).append(body.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
                continue;
            }
            if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 去掉 SD 参数值两侧的引号。
     *
     * @param value 原始 token
     * @return 去引号后的文本
     */
    private static String stripSdQuotes(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * SD 转义：反斜杠、双引号、右括号。
     *
     * @param value 原文
     * @return 转义后文本
     */
    private static String escapeSd(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"' || c == ']') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * SD 反转义。
     *
     * @param value 转义文本
     * @return 原文
     */
    private static String unescapeSd(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                sb.append(value.charAt(i + 1));
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 解析 RFC 3164 报文（无年份，取当前年份；本地时区）。
     *
     * @param matcher 已匹配的 RFC 3164 正则
     * @return 消息对象
     */
    private static SyslogMessage parseRfc3164(Matcher matcher) {
        int pri = parsePri(matcher.group(1));
        Instant timestamp = parseRfc3164Timestamp(
                matcher.group(2), matcher.group(3),
                matcher.group(4), matcher.group(5), matcher.group(6));
        return builder()
                .facility(pri / 8)
                .severity(pri % 8)
                .timestamp(timestamp)
                .hostname(matcher.group(7))
                .appName(matcher.group(8))
                .message(matcher.group(9))
                .build();
    }

    /**
     * 解析 PRI 数字，越界时回落到 user.info。
     *
     * @param priText PRI 数字文本
     * @return 合法 PRI
     */
    private static int parsePri(String priText) {
        int pri;
        try {
            pri = Integer.parseInt(priText);
        } catch (NumberFormatException e) {
            return DEFAULT_PRI;
        }
        if (pri < 0 || pri > MAX_PRI) {
            return DEFAULT_PRI;
        }
        return pri;
    }

    /**
     * 解析 RFC 5424 ISO 8601 时间戳，失败时取当前时间。
     *
     * @param text 时间戳文本
     * @return Instant
     */
    private static Instant parseIsoTimestamp(String text) {
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    /**
     * 解析 RFC 3164 本地时间（无年份，按当前年补齐）。
     *
     * @param monthAbbr 英文月份缩写
     * @param day       日（可含前导空格）
     * @param hh        时
     * @param mm        分
     * @param ss        秒
     * @return Instant，解析失败时取当前时间
     */
    private static Instant parseRfc3164Timestamp(String monthAbbr, String day, String hh, String mm, String ss) {
        try {
            int month = monthOf(monthAbbr);
            int year = LocalDate.now().getYear();
            return LocalDateTime.of(year, month, Integer.parseInt(day.trim()),
                            Integer.parseInt(hh), Integer.parseInt(mm), Integer.parseInt(ss))
                    .atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    /**
     * 英文月份缩写转月份数值，未知时取当前月份。
     *
     * @param abbreviation 缩写
     * @return 1-12
     */
    private static int monthOf(String abbreviation) {
        String key = abbreviation == null ? "" : abbreviation.toLowerCase();
        for (int i = 0; i < MONTH_ABBREVIATIONS.length; i++) {
            if (MONTH_ABBREVIATIONS[i].toLowerCase().equals(key)) {
                return i + 1;
            }
        }
        return LocalDate.now().getMonthValue();
    }

    /**
     * 兜底消息：user.info，原文保留。
     *
     * @param line 规范化后的行
     * @return 消息对象
     */
    private static SyslogMessage fallback(String line) {
        return builder().message(line).build();
    }

    /**
     * 空值转 RFC NIL（{@code -}）。
     *
     * @param value 原文
     * @return NIL 或原文
     */
    private static String nilIfEmpty(String value) {
        return StringUtils.isEmpty(value) ? "-" : value;
    }

    /**
     * RFC NIL（{@code -}）转空值。
     *
     * @param value 原文
     * @return null 或原文
     */
    private static String dashToNull(String value) {
        return value == null || "-".equals(value) ? null : value;
    }

    /**
     * 默认主机名。
     *
     * @return 本机主机名，获取失败时为 unknown
     */
    private static String defaultHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
