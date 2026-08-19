package com.chua.filesystem.log.support.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.filesystem.log.support.bridge.NativeFunctionRegistry;
import com.chua.filesystem.log.support.bridge.PlatformSystems;
import com.chua.filesystem.log.support.bridge.SystemLogBridge;
import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogLevel;
import com.chua.filesystem.log.support.model.LogQuery;
import com.chua.filesystem.log.support.spi.SystemLogProvider;
import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Linux 系统日志提供者 - libsystemd FFM + /var/log 文件回退
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("linux")
@SpiDescribe(value = "linux-journald", desc = "Linux 系统日志提供者(journald + /var/log)", type = "log")
@Slf4j
public class LinuxJournaldProvider implements SystemLogProvider {

    /** Sources */
    private static final List<String> SOURCES = Arrays.asList(
            "journald", "syslog", "auth", "kern", "daemon", "cron", "user"
    );

    /** Var_log_files */
    private static final List<String> VAR_LOG_FILES = Arrays.asList(
            "/var/log/syslog",
            "/var/log/messages",
            "/var/log/auth.log",
            "/var/log/kern.log",
            "/var/log/daemon.log"
    );

    /** Timestamp_formatter */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    /** 注册表 */
    private final NativeFunctionRegistry registry;

    /** sdJournalOpen */
    private volatile MethodHandle sdJournalOpen;
    /** sdJournalAddMatch */
    private volatile MethodHandle sdJournalAddMatch;
    /** sdJournalNext */
    private volatile MethodHandle sdJournalNext;
    /** sdJournalPrevious */
    private volatile MethodHandle sdJournalPrevious;
    /** sdJournalGetData */
    private volatile MethodHandle sdJournalGetData;
    /** sdJournalClose */
    private volatile MethodHandle sdJournalClose;
    /** sdJournalSeekTail */
    private volatile MethodHandle sdJournalSeekTail;
    /** sdJournalSeekHead */
    private volatile MethodHandle sdJournalSeekHead;
    /** sdJournalGetCursor */
    private volatile MethodHandle sdJournalGetCursor;
    /** sdJournalSeekCursor */
    private volatile MethodHandle sdJournalSeekCursor;

    /**
     * 创建 LinuxJournaldProvider 实例
     * @param bridge bridge
     */
    public LinuxJournaldProvider(SystemLogBridge bridge) {
        if (bridge != null) {
            this.registry = bridge.getLinuxRegistry();
        } else {
            this.registry = NativeFunctionRegistry.ofLibrary("systemd");
        }
    }

    @Override
    /** 是否PlatformSupported */
    public boolean isPlatformSupported() {
        
        return PlatformSystems.isLinux();
    
    }

    @Override
    /** 获取Sources */
    public List<String> getSources() {
        
        return SOURCES;
    
    }

    @Override
    /** 搜索 */
    public List<LogEntry> search(LogQuery query) {
        if (!isPlatformSupported()) {
            return List.of();
        }

        if (registry != null && isJournaldAvailable()) {
            try {
                return searchViaJournald(query);
            } catch (Exception e) {
                log.warn("libsystemd search failed, falling back to /var/log: {}", e.getMessage());
            }
        }

        return searchViaVarLog(query);
    }

    /** 是否JournaldAvailable */
    private boolean isJournaldAvailable() {
        try {
            bindFunctions();
            return sdJournalOpen != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 搜索ViaJournald */
    private List<LogEntry> searchViaJournald(LogQuery query) {
        List<LogEntry> results = new ArrayList<>();
        MemorySegment journal = null;

        try (Arena arena = Arena.ofConfined()) {
            bindFunctions();

            MemorySegment journalPtr = arena.allocate(ValueLayout.ADDRESS);
            int rc = (int) sdJournalOpen.invokeExact(journalPtr, 0 /* SD_JOURNAL_LOCAL_ONLY */);
            if (rc < 0) {
                log.warn("sd_journal_open failed: {}", rc);
                return List.of();
            }

            journal = journalPtr.get(ValueLayout.ADDRESS, 0);
            if (MemorySegment.NULL.equals(journal)) {
                return List.of();
            }

            boolean ascending = LogQuery.ORDER_ASC.equals(query.order());
            if (ascending) {
                sdJournalSeekHead.invokeExact(journal);
            } else {
                sdJournalSeekTail.invokeExact(journal);
            }

            int maxResults = query.maxResults();
            int scanned = 0;
            int scanLimit = Math.max(maxResults * 10, 2000);

            while (results.size() < maxResults && scanned < scanLimit) {
                int nextRc;
                if (ascending) {
                    nextRc = (int) sdJournalNext.invokeExact(journal);
                } else {
                    nextRc = (int) sdJournalPrevious.invokeExact(journal);
                }
                scanned++;
                if (nextRc <= 0) {
                    break;
                }

                String message = getJournalField(journal, arena, "MESSAGE");
                if (message == null) {
                    continue;
                }

                String source = getJournalField(journal, arena, "_SYSTEMD_UNIT");
                if (source == null) {
                    source = getJournalField(journal, arena, "SYSLOG_IDENTIFIER");
                }

                String priorityStr = getJournalField(journal, arena, "PRIORITY");
                LogLevel level = parseJournalPriority(priorityStr);

                if (!level.meetsMinimum(query.minLevel())) {
                    continue;
                }

                Pattern regex = compilePattern(query.pattern());
                String searchText = (source != null ? source + " " : "") + message;
                if (regex != null && !regex.matcher(searchText).find()) {
                    continue;
                }

                String tsStr = getJournalField(journal, arena, "_SOURCE_REALTIME_TIMESTAMP");
                String timestamp = formatTimestamp(tsStr);

                results.add(new LogEntry(
                        timestamp,
                        level,
                        source != null ? source : "journald",
                        message,
                        "linux",
                        null
                ));
            }

        } catch (Throwable e) {
            log.error("Error reading journald: {}", e.getMessage(), e);
        } finally {
            if (journal != null && !MemorySegment.NULL.equals(journal)) {
                try {
                    sdJournalClose.invokeExact(journal);
                } catch (Throwable e) {
                    log.trace("Error closing journal", e);
                }
            }
        }

        return results;
    }

    /** 获取JournalField */
    private String getJournalField(MemorySegment journal, Arena arena, String field) {
        try {
            MemorySegment dataPtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment lenPtr = arena.allocate(ValueLayout.JAVA_LONG);

            MemorySegment fieldName = arena.allocateFrom(field);
            int rc = (int) sdJournalGetData.invokeExact(
                    journal, fieldName,
                    dataPtr, lenPtr
            );

            if (rc < 0) {
                return null;
            }

            MemorySegment dataAddr = dataPtr.get(ValueLayout.ADDRESS, 0);
            long len = lenPtr.get(ValueLayout.JAVA_LONG, 0);

            if (MemorySegment.NULL.equals(dataAddr) || len <= 0) {
                return null;
            }

            MemorySegment data = MemorySegment.ofAddress(dataAddr.address());
            byte[] bytes = data.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
            String raw = new String(bytes, StandardCharsets.UTF_8);
            int eqIdx = raw.indexOf('=');
            return eqIdx >= 0 ? raw.substring(eqIdx + 1) : raw;

        } catch (Throwable e) {
            log.trace("Error reading journal field '{}': {}", field, e.getMessage());
            return null;
        }
    }

    /** 解析JournalPriority */
    private LogLevel parseJournalPriority(String priorityStr) {
        if (priorityStr == null) {
            return LogLevel.INFO;
        }
        try {
            int p = Integer.parseInt(priorityStr.trim());
            return switch (p) {
                case 0, 1 -> LogLevel.CRITICAL;
                case 2     -> LogLevel.CRITICAL;
                case 3     -> LogLevel.ERROR;
                case 4     -> LogLevel.WARNING;
                case 5, 6  -> LogLevel.INFO;
                case 7     -> LogLevel.DEBUG;
                default    -> LogLevel.INFO;
            };
        } catch (NumberFormatException e) {
            return LogLevel.INFO;
        }
    }

    /** 格式化Timestamp */
    private String formatTimestamp(String tsStr) {
        if (tsStr == null) {
            return "unknown";
        }
        try {
            long micros = Long.parseLong(tsStr.trim());
            return TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(micros / 1000));
        } catch (NumberFormatException e) {
            return tsStr;
        }
    }

    /** 搜索ViaVar记录日志 */
    private List<LogEntry> searchViaVarLog(LogQuery query) {
        log.debug("Searching /var/log files with pattern={}", query.pattern());
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
                    LogEntry entry = parseLogLine(line, logFile);
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

        if (LogQuery.ORDER_DESC.equals(query.order())) {
            Collections.reverse(results);
        }
        return results;
    }

    /** 解析记录日志Line */
    private LogEntry parseLogLine(String line, String source) {
        if (line == null || line.isBlank()) { return null; }
        try {
            String timestamp = "unknown";
            String message = line;
            LogLevel level = detectLevelFromMessage(line);

            if (line.length() > 15) {
                timestamp = line.substring(0, 15).trim();
            }

            return new LogEntry(timestamp, level,
                    source.replace("/var/log/", "").replace(".log", ""),
                    message, "linux", null);
        } catch (Exception e) {
            return new LogEntry("unknown", LogLevel.INFO, source, line, "linux", null);
        }
    }

    /** DetectLevelFromMessage */
    private LogLevel detectLevelFromMessage(String line) {
        if (line == null) {
            return LogLevel.INFO;
        }
        String lower = line.toLowerCase();
        if (lower.contains("error") || lower.contains("fail") || lower.contains("critical")) {
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

    /** CompilePattern */
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

    @SuppressWarnings("unchecked")
    /** 绑定Functions */
    private void bindFunctions() {
        if (sdJournalOpen == null) {
            synchronized (this) {
                if (sdJournalOpen == null) {
                    sdJournalOpen   = registry.require("sd_journal_open",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                    sdJournalAddMatch = registry.require("sd_journal_add_match",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                    sdJournalNext   = registry.require("sd_journal_next",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                    sdJournalPrevious = registry.require("sd_journal_previous",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                    sdJournalGetData = registry.require("sd_journal_get_data",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    sdJournalClose  = registry.require("sd_journal_close",
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                    sdJournalSeekTail = registry.register("sd_journal_seek_tail",
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                    sdJournalSeekHead = registry.register("sd_journal_seek_head",
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                    sdJournalGetCursor = registry.register("sd_journal_get_cursor",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    sdJournalSeekCursor = registry.register("sd_journal_seek_cursor",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                }
            }
        }
    }
}

