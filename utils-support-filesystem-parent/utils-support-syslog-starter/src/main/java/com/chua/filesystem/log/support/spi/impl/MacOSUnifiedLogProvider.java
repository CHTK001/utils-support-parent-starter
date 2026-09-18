package com.chua.filesystem.log.support.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.filesystem.log.support.bridge.PlatformSystems;
import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogLevel;
import com.chua.filesystem.log.support.model.LogQuery;
import com.chua.filesystem.log.support.spi.SystemLogProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
* macOS 系统日志提供者 - 日志 show 命令 + /var/日志 文件回退
*
* @author CH
* @since 4.0.0.42
 */
@Spi("macos")
@SpiDescribe(value = "macos-unified-log", desc = "macOS 系统日志提供者(log show + /var/log)", type = "log")
@Slf4j
public class MacOSUnifiedLogProvider implements SystemLogProvider {

    /** 源 */
    private static final List<String> SOURCES = Arrays.asList(
            "unified", "system", "install", "kernel"
    );

    /** Var_日志_文件 */
    private static final List<String> VAR_LOG_FILES = Arrays.asList(
            "/var/log/system.log",
            "/var/log/install.log"
    );

    /** 日志_show_CMD */
    private static final String LOG_SHOW_CMD = "/usr/bin/log";

    @Override
    /** 是否platform支持 */
    public boolean isPlatformSupported() {
        
        return PlatformSystems.isMacOs();
    
    }

    @Override
    /** 获取源 */
    public List<String> getSources() {
        
        return SOURCES;
    
    }

    @Override
    /** 搜索 */
    public List<LogEntry> search(LogQuery query) {
        if (!isPlatformSupported()) {
            return List.of();
        }

        try {
            List<LogEntry> result = searchViaLogShow(query);
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception e) {
            log.warn("log show failed, falling back to /var/log: {}", e.getMessage());
        }

        return searchViaVarLog(query);
    }

    /**
    * 搜索Via记录日志Show
    *
    * @param query 查询
    * @return 搜索via日志show的结果
    */
    private List<LogEntry> searchViaLogShow(LogQuery query) {
        List<String> args = new ArrayList<>();
        args.add(LOG_SHOW_CMD);
        args.add("show");

        if (query.pattern() != null && !query.pattern().isEmpty()) {
            String predicate = buildPredicate(query);
            args.add("--predicate");
            args.add(predicate);
        }

        args.add("--style");
        args.add("syslog");
        args.add("--last");
        args.add("24h");
        args.add("--color");
        args.add("none");

        if (log.isDebugEnabled()) {
            log.debug("Executing: {}", String.join(" ", args));
        }

        List<LogEntry> results = new ArrayList<>();
        int maxResults = query.maxResults();

        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Pattern regex = compilePattern(query.pattern());

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null && results.size() < maxResults) {
                    LogEntry entry = parseLogShowLine(line);
                    if (entry == null) {
                        continue;
                    }
                    if (!entry.level().meetsMinimum(query.minLevel())) {
                        continue;
                    }
                    if (regex != null && !regex.matcher(entry.message()).find()) {
                        continue;
                    }
                    results.add(entry);
                }
            }

            process.waitFor();
        } catch (Exception e) {
            log.error("Error executing log show: {}", e.getMessage(), e);
        }

        Collections.reverse(results);
        return results;
    }

    /**
    * 构建Predicate
    *
    * @param query 查询
    * @return 构建predicate的结果
    */
    private String buildPredicate(LogQuery query) {
        StringBuilder sb = new StringBuilder();
        String p = query.pattern() != null ? query.pattern() : "";

        String clean = p.replace("*", "").replace("?", "");
        if (!clean.isEmpty()) {
            sb.append("eventMessage CONTAINS \"").append(clean).append("\"");
        }

        if (query.minLevel() != null) {
            if (!sb.isEmpty()) { sb.append(" AND "); }
            switch (query.minLevel()) {
                case ERROR, CRITICAL -> sb.append("messageType >= error");
                case WARNING -> sb.append("messageType >= default");
                default -> sb.append("messageType >= info");
            }
        }

        if (sb.isEmpty()) {
            sb.append("eventMessage CONTAINS \"\"");
        }
        return sb.toString();
    }

    /**
    * 解析记录日志show线
    *
    * @param line 线
    * @return 解析日志show线的结果
    */
    private LogEntry parseLogShowLine(String line) {
        if (line == null || line.isBlank()) { return null; }
        try {
            int firstSpace = line.indexOf(' ');
            if (firstSpace <= 0) {
                return null;
            }

            String timePart = line.substring(0, firstSpace);
            String rest = line.substring(firstSpace + 1).trim();

            String source = "unified";
            int bracketIdx = rest.indexOf('[');
            if (bracketIdx > 0) {
                int wsBefore = rest.lastIndexOf(' ', bracketIdx);
                if (wsBefore > 0) {
                    source = rest.substring(wsBefore + 1, bracketIdx);
                }
            }

            String message = rest;
            int msgStart = rest.indexOf("): ");
            if (msgStart > 0) {
                message = rest.substring(msgStart + 3);
            }

            LogLevel level = detectLevelFromMessage(message);

            return new LogEntry(timePart, level, source, message, "macos", null);
        } catch (Exception e) {
            return new LogEntry("unknown", LogLevel.INFO, "unified", line, "macos", null);
        }
    }

    /**
    * 搜索viavar记录日志
    *
    * @param query 查询
    * @return 搜索viavar日志的结果
    */
    private List<LogEntry> searchViaVarLog(LogQuery query) {
        List<LogEntry> results = new ArrayList<>();
        Pattern regex = compilePattern(query.pattern());
        int maxResults = query.maxResults();

        for (String logFile : VAR_LOG_FILES) {
            Path path = Paths.get(logFile);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                continue;
            }
            if (results.size() >= maxResults) { break; }

            try (Stream<String> lines = Files.lines(path)) {
                lines.forEach(line -> {
                    if (results.size() >= maxResults) { return; }
                    LogEntry entry = parseVarLogLine(line, logFile);
                    if (entry == null) {
                        return;
                    }
                    if (!entry.level().meetsMinimum(query.minLevel())) {
                        return;
                    }
                    if (regex != null && !regex.matcher(entry.message()).find()) {
                        return;
                    }
                    results.add(entry);
                });
            } catch (Exception e) {
                log.trace("Error reading {}: {}", logFile, e.getMessage());
            }
        }

        Collections.reverse(results);
        return results;
    }

    /**
    * 解析Var记录日志线
    *
    * @param line 线
    * @param source 源
    * @return 解析var日志线的结果
    */
    private LogEntry parseVarLogLine(String line, String source) {
        if (line == null || line.isBlank()) { return null; }
        String timestamp = "unknown";
        if (line.length() > 15) {
            timestamp = line.substring(0, 15).trim();
        }
        LogLevel level = detectLevelFromMessage(line);
        return new LogEntry(timestamp, level,
                source.replace("/var/log/", "").replace(".log", ""),
                line, "macos", null);
    }

    /**
    * detect级别从消息
    *
    * @param line 线
    * @return detect级别从消息的结果
    */
    private LogLevel detectLevelFromMessage(String line) {
        if (line == null) {
            return LogLevel.INFO;
        }
        String lower = line.toLowerCase();
        if (lower.contains("fault") || lower.contains("critical") || lower.contains("emergency")) {
            return LogLevel.CRITICAL;
        }
        if (lower.contains("error") || lower.contains("fail")) {
            return LogLevel.ERROR;
        }
        if (lower.contains("warn") || lower.contains("warning")) {
            return LogLevel.WARNING;
        }
        if (lower.contains("debug") || lower.contains("trace")) {
            return LogLevel.DEBUG;
        }
        return LogLevel.INFO;
    }

    /**
    * compile模式
    *
    * @param glob glob
    * @return compile模式的结果
    */
    private Pattern compilePattern(String glob) {
        if (glob == null || glob.isEmpty()) { return null; }
        try {
            String regex = glob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            log.warn("Invalid glob pattern: {}", glob);
            return null;
        }
    }
}

