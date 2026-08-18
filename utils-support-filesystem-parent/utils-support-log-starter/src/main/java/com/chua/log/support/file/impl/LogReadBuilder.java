package com.chua.log.support.file.impl;

import com.chua.common.support.file.builder.ReadBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 日志文件读取构建器。
 *
 * <p>支持以下日志读取能力：</p>
 * <ul>
 *     <li>{@link #grep(String)} — 按关键词过滤行</li>
 *     <li>{@link #grep(String, boolean)} — 按正则表达式过滤行</li>
 *     <li>{@link #tail(int)} — 仅读取末尾 N 行</li>
 *     <li>{@link #timeRange(LocalDateTime, LocalDateTime)} — 按时间范围过滤</li>
 *     <li>{@link #withTimestampFormat(String)} — 自定义日志时间戳格式</li>
 * </ul>
 *
 * <p>以上过滤条件可叠加使用（AND 逻辑）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LogReadBuilder extends ReadBuilder {

    /**
     * 常见日志时间戳格式列表（按优先级降序匹配）
     */
    private static final DateTimeFormatter[] DEFAULT_TIMESTAMP_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    };

    /** 关键词过滤（子串匹配） */
    /** Keyword */
    private String keyword;

    /** 正则过滤 Pattern */
    /** Regex模式 */
    private Pattern regexPattern;

    /** 尾部行数（> 0 时启用 tail） */
    /** Taillines */
    private int tailLines;

    /** 时间范围下限（null 不限） */
    /** 时间from */
    private LocalDateTime timeFrom;

    /** 时间范围上限（null 不限） */
    /** 时间TO */
    private LocalDateTime timeTo;

    /** 自定义时间戳格式（null 则自动检测） */
    /** 时间戳格式 */
    private DateTimeFormatter timestampFormat;

    /** 是否反转过滤（类似 grep -v） */
    /** Invertmatch */
    private boolean invertMatch;

    /**
     * 构造日志读取构建器。
     *
     * @param file 日志文件
     */
    public LogReadBuilder(File file) {
        super(file);
    }

    // ==================== 链式配置 ====================

    /**
     * 设置关键词过滤（子串包含匹配）。
     *
     * @param keyword 要匹配的关键词
     * @return 当前构建器
     */
    public LogReadBuilder grep(String keyword) {
        this.keyword = keyword;
        this.regexPattern = null;
        return this;
    }

    /**
     * 设置正则表达式过滤。
     *
     * @param pattern 正则表达式
     * @param regex   是否启用正则模式（{@code true} 为正则，{@code false} 为普通字符串）
     * @return 当前构建器
     */
    public LogReadBuilder grep(String pattern, boolean regex) {
        if (regex) {
            this.regexPattern = Pattern.compile(pattern);
            this.keyword = null;
        } else {
            this.keyword = pattern;
            this.regexPattern = null;
        }
        return this;
    }

    /**
     * 启用反转匹配（类似 {@code grep -v}），仅返回不匹配的行。
     *
     * @return 当前构建器
     */
    public LogReadBuilder invertMatch() {
        this.invertMatch = true;
        return this;
    }

    /**
     * 仅读取日志文件尾部 N 行（类似 {@code tail -n}）。
     *
     * @param lines 行数
     * @return 当前构建器
     */
    public LogReadBuilder tail(int lines) {
        this.tailLines = Math.max(0, lines);
        return this;
    }

    /**
     * 设置时间范围下限，仅返回该时间点之后的日志行。
     *
     * @param from 起始时间（含）
     * @return 当前构建器
     */
    public LogReadBuilder timeFrom(LocalDateTime from) {
        this.timeFrom = from;
        return this;
    }

    /**
     * 设置时间范围上限，仅返回该时间点之前的日志行。
     *
     * @param to 结束时间（含）
     * @return 当前构建器
     */
    public LogReadBuilder timeTo(LocalDateTime to) {
        this.timeTo = to;
        return this;
    }

    /**
     * 设置时间范围（闭区间），仅返回该时间段内的日志行。
     *
     * @param from 起始时间
     * @param to   结束时间
     * @return 当前构建器
     */
    public LogReadBuilder timeRange(LocalDateTime from, LocalDateTime to) {
        this.timeFrom = from;
        this.timeTo = to;
        return this;
    }

    /**
     * 自定义日志时间戳格式。
     * <p>默认自动识别以下格式：</p>
     * <ul>
     *     <li>{@code yyyy-MM-dd HH:mm:ss,SSS}</li>
     *     <li>{@code yyyy-MM-dd HH:mm:ss.SSS}</li>
     *     <li>{@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX}</li>
     *     <li>{@code yyyy-MM-dd HH:mm:ss}</li>
     *     <li>{@code yyyy/MM/dd HH:mm:ss}</li>
     *     <li>{@code dd/MMM/yyyy:HH:mm:ss Z}</li>
     * </ul>
     *
     * @param pattern {@link DateTimeFormatter} 格式
     * @return 当前构建器
     */
    public LogReadBuilder withTimestampFormat(String pattern) {
        this.timestampFormat = DateTimeFormatter.ofPattern(pattern);
        return this;
    }

    @Override
    public LogReadBuilder withCharset(String charset) {
        super.withCharset(charset);
        return this;
    }

    // ==================== 读取方法 ====================

    /**
     * 读取日志文件全部行，应用已设置的过滤条件。
     *
     * @return 过滤后的行列表
     */
    public List<String> lines() {
        List<String> allLines = readAllLines();
        if (allLines.isEmpty()) {
            return allLines;
        }

        // 应用 grep 过滤
        List<String> filtered = applyGrepFilter(allLines);

        // 应用时间范围过滤
        filtered = applyTimeFilter(filtered);

        // 应用 tail
        filtered = applyTail(filtered);

        if (callback != null) {
            for (String line : filtered) {
                callback.onBody(line);
            }
            callback.onComplete(filtered.size());
        }

        return filtered;
    }

    /**
     * 以流式方式逐行读取，不一次性加载全部内容到内存。
     * <p>注意：tail 和 时间过滤 在流式模式下可能不准确（需要回溯）。</p>
     *
     * @return 行文本流
     */
    @Override
    public java.util.stream.Stream<String> streamLines() {
        return lines().stream();
    }

    @Override
    public List<String> asLines() {
        return lines();
    }

    @Override
    public String asString() {
        return String.join(System.lineSeparator(), lines());
    }

    @Override
    public Object read() {
        return lines();
    }

    // ==================== 内部方法 ====================

    /**
     * 从文件读取全部行。
     */
    private List<String> readAllLines() {
        List<String> result = new ArrayList<>();
        if (file == null || !file.exists() || !file.isFile()) {
            return result;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.add(line);
            }
        } catch (IOException e) {
            // 读取失败返回空列表
        }
        return result;
    }

    /**
     * 应用 grep 关键词 / 正则过滤。
     */
    private List<String> applyGrepFilter(List<String> lines) {
        if (keyword == null && regexPattern == null) {
            return lines;
        }

        return lines.stream()
                .filter(line -> {
                    boolean matches;
                    if (regexPattern != null) {
                        matches = regexPattern.matcher(line).find();
                    } else {
                        matches = line.contains(keyword);
                    }
                    return invertMatch != matches;
                })
                .collect(Collectors.toList());
    }

    /**
     * 应用时间范围过滤。
     */
    private List<String> applyTimeFilter(List<String> lines) {
        if (timeFrom == null && timeTo == null) {
            return lines;
        }
        return lines.stream()
                .filter(line -> isWithinTimeRange(line))
                .collect(Collectors.toList());
    }

    /**
     * 判断行内的时间戳是否在指定时间范围内。
     */
    private boolean isWithinTimeRange(String line) {
        LocalDateTime lineTime = extractTimestamp(line);
        if (lineTime == null) {
            // 无法解析时间戳的行默认保留
            return true;
        }
        if (timeFrom != null && lineTime.isBefore(timeFrom)) {
            return false;
        }
        if (timeTo != null && lineTime.isAfter(timeTo)) {
            return false;
        }
        return true;
    }

    /**
     * 从日志行中提取时间戳。
     * <p>遍历默认格式列表，取第一个匹配的格式进行解析。</p>
     */
    private LocalDateTime extractTimestamp(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        // 取行首 30 个字符做时间戳识别
        String prefix = line.length() > 30 ? line.substring(0, 30) : line;

        // 先尝试自定义格式
        if (timestampFormat != null) {
            try {
                return LocalDateTime.parse(prefix.trim(), timestampFormat);
            } catch (DateTimeParseException ignored) {
                // fallthrough
            }
        }

        // 尝试默认格式列表
        for (DateTimeFormatter fmt : DEFAULT_TIMESTAMP_FORMATS) {
            try {
                return LocalDateTime.parse(prefix.trim(), fmt);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }
        return null;
    }

    /**
     * 应用 tail 截取尾部 N 行。
     */
    private List<String> applyTail(List<String> lines) {
        if (tailLines <= 0 || lines.size() <= tailLines) {
            return lines;
        }
        return lines.subList(lines.size() - tailLines, lines.size());
    }

    // ==================== 流式读取增强 ====================

    /**
     * 逐行回调读取（流式不缓存所有行到内存）。
     *
     * @param lineHandler 每行处理函数
     */
    public void readLines(java.util.function.Consumer<String> lineHandler) {
        if (file == null || !file.exists() || !file.isFile()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (passesGrep(line) && isWithinTimeRange(line)) {
                    lineHandler.accept(line);
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * 判断单行是否通过 grep 过滤。
     */
    private boolean passesGrep(String line) {
        if (keyword == null && regexPattern == null) {
            return true;
        }
        boolean matches;
        if (regexPattern != null) {
            matches = regexPattern.matcher(line).find();
        } else {
            matches = line.contains(keyword);
        }
        return invertMatch != matches;
    }
}
