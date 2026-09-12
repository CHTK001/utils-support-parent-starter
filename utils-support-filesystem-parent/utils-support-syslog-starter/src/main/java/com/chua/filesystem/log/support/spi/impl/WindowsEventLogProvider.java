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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
   * 窗口 系统日志提供者 - 通过 Java 25 FFM 直调 advapi32 事件 日志 API
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("windows")
@SpiDescribe(value = "windows-event-log", desc = "Windows 系统事件日志提供者", type = "log")
@Slf4j
public class WindowsEventLogProvider implements SystemLogProvider {

    /** 源 */
    private static final List<String> SOURCES = List.of("System", "Application", "Security");

    /** Eventlog_sequential_读取 */
    private static final int EVENTLOG_SEQUENTIAL_READ = 0x0001;
    /** Eventlog_远期_读取 */
    private static final int EVENTLOG_FORWARDS_READ    = 0x0004;
    /** Eventlog_seek_读取 */
    private static final int EVENTLOG_SEEK_READ        = 0x0002;

    /** 缓冲_大小 */
    private static final int BUFFER_SIZE = 65536;

    /** 时间戳_formatter */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    /** 注册表 */
    private final NativeFunctionRegistry registry;

    /** 打开事件日志 */
    private volatile MethodHandle openEventLog;
    /** 读取事件日志 */
    private volatile MethodHandle readEventLog;
    /** 关闭事件日志 */
    private volatile MethodHandle closeEventLog;
    /** 获取数字的事件日志records */
    private volatile MethodHandle getNumberOfEventLogRecords;
    /** 获取最后一个错误处理 */
    private volatile MethodHandle getLastErrorHandle;

    /**
      * 创建 窗口事件日志提供者 实例
     * @param bridge bridge
     */
    public WindowsEventLogProvider(SystemLogBridge bridge) {
        if (bridge != null) {
            this.registry = bridge.getWin32Registry();
        } else {
            this.registry = NativeFunctionRegistry.ofLibrary("Advapi32");
        }
        if (this.registry != null) {
            try {
                var kernel32Registry = NativeFunctionRegistry.ofLibrary("Kernel32");
                if (kernel32Registry != null) {
                    getLastErrorHandle = kernel32Registry.register("GetLastError",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT));
                }
            } catch (Exception e) {
                log.trace("Cannot bind GetLastError via FFM", e);
            }
        }
    }

    @Override
    /** 是否platform支持 */
    public boolean isPlatformSupported() {
        
        return PlatformSystems.isWindows() && registry != null;
    
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
            log.warn("WindowsEventLogProvider not supported on current platform");
            return List.of();
        }

        String source = query.source() != null ? query.source() : "System";
        String pattern = query.pattern();
        LogLevel minLevel = query.minLevel();
        int maxResults = query.maxResults();

        List<LogEntry> results = new ArrayList<>();
        MemorySegment hEventLog = null;

        try (Arena arena = Arena.ofConfined()) {
            bindFunctions();

            MemorySegment serverName = MemorySegment.NULL;
            MemorySegment sourceName = arena.allocateFrom(source, java.nio.charset.StandardCharsets.UTF_16LE);
            Object handleObj = openEventLog.invoke(serverName, sourceName);
            hEventLog = coerceToMemorySegment(handleObj);

            if (hEventLog == null || MemorySegment.NULL.equals(hEventLog)) {
                log.warn("Failed to open event log source: {}", source);
                return List.of();
            }

            MemorySegment totalRecords = arena.allocate(ValueLayout.JAVA_INT);
            getNumberOfEventLogRecords.invoke(hEventLog, totalRecords);
            int total = totalRecords.get(ValueLayout.JAVA_INT, 0);
            if (total == 0) {
                return List.of();
            }

            MemorySegment buffer = arena.allocate(BUFFER_SIZE);
            MemorySegment bytesRead = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment minBytesNeeded = arena.allocate(ValueLayout.JAVA_INT);

            Pattern regex = compilePattern(pattern);

            int offset = 0;
            int readIterations = 0;
            while (results.size() < maxResults && readIterations < 200) {
                readIterations++;
                bytesRead.set(ValueLayout.JAVA_INT, 0, 0);
                minBytesNeeded.set(ValueLayout.JAVA_INT, 0, 0);

                int readResult = (int) readEventLog.invoke(
                        hEventLog,
                        EVENTLOG_SEQUENTIAL_READ | EVENTLOG_FORWARDS_READ,
                        0,
                        buffer,
                        BUFFER_SIZE,
                        bytesRead,
                        minBytesNeeded
                );

                if (readResult == 0) {
                    break;
                }

                int read = bytesRead.get(ValueLayout.JAVA_INT, 0);
                if (read == 0) {
                    break;
                }

                int parsed = parseEventLogRecords(buffer, read, source, regex, minLevel, maxResults - results.size(), results);
                offset += read;
                if (parsed == 0) {
                    break;
                }
            }

        } catch (Throwable e) {
            log.error("Error reading Windows event log: {}", e.getMessage(), e);
        } finally {
            if (hEventLog != null && !MemorySegment.NULL.equals(hEventLog)) {
                try {
                    closeEventLog.invoke(hEventLog);
                } catch (Throwable e) {
                    log.trace("Error closing event log handle", e);
                }
            }
        }

        return results;
    }

    /**
      * 解析事件日志records
     * @param buffer 缓冲
     * @param bytesRead bytes读取
     * @param source 源
     * @param regex regex
     * @param minLevel 最小级别
     * @param remaining remaining
     * @param results 结果
     * @param remaining remaining
     * @param offset 偏移量
     * @param bytesRead bytes读取
     * @param offset 偏移量
     * @param stringOffset 字符串偏移量
     * @param message 消息
     * @param level 级别
     * @param source 源
     * @param message 消息
     * @param null 空
     * @param buffer 缓冲
     * @param recordOffset record偏移量
     * @param stringOffset 字符串偏移量
     * @param i i
     * @param eventType 事件类型
     * @param glob glob
     * @param e e
     * @param glob glob
     * @param handleObj 处理obj
     * @param seg seg
     * @param num num
     * @param e e
     * @param e e
     */
    public static int parseEventLogRecords(
            MemorySegment buffer, int bytesRead,
            String source,
            Pattern regex, LogLevel minLevel,
            int remaining, List<LogEntry> results
    ) {
        int parsed = 0;
        int offset = 0;

        while (offset < bytesRead && results.size() < remaining) {
            int length = buffer.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
            if (length == 0 || offset + length > bytesRead) {
                break;
            }

            int timeGenerated = buffer.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 12);
            short eventType = buffer.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset + 24);

            LogLevel level = mapEventTypeToLevel(eventType);
            if (!level.meetsMinimum(minLevel)) {
                offset += length;
                parsed++;
                continue;
            }

            int stringOffset = buffer.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 36);
            String message = extractString(buffer, offset, stringOffset);
            if (message == null || message.isEmpty()) {
                message = "(no message)";
            }

            if (regex != null && !regex.matcher(message).find()) {
                offset += length;
                parsed++;
                continue;
            }

            String timestamp = TIMESTAMP_FORMATTER.format(
                    Instant.ofEpochSecond(timeGenerated & 0xFFFFFFFFL)
            );

            results.add(new LogEntry(
                    timestamp, level, source, message, "windows", null
            ));

            offset += length;
            parsed++;
        }

        return parsed;
    }

    /**
     * extract字符串
     *
     * @param buffer 缓冲
     * @param recordOffset record偏移量
     * @param stringOffset 字符串偏移量
     * @return extract字符串的结果
     */
    public static String extractString(MemorySegment buffer, int recordOffset, int stringOffset) {
        int stringsStart = recordOffset + stringOffset;
        if (stringsStart <= 0 || stringsStart >= (int) buffer.byteSize()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        long size = buffer.byteSize();
        for (long i = stringsStart; i + 1 < size; i += 2) {
            char c = (char) (buffer.get(ValueLayout.JAVA_SHORT_UNALIGNED, i) & 0xFFFF);
            if (c == 0) {
                break;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * 映射事件类型转为级别
     *
     * @param eventType 事件类型
     * @return 映射事件类型转为级别的结果
     */
    public static LogLevel mapEventTypeToLevel(short eventType) {
        return switch (eventType) {
            case 1  -> LogLevel.ERROR;
            case 2  -> LogLevel.WARNING;
            case 16 -> LogLevel.ERROR;
            default -> LogLevel.INFO;
        };
    }

    /**
     * compile模式
     *
     * @param glob glob
     * @return compile模式的结果
     */
    private Pattern compilePattern(String glob) {
        if (glob == null || glob.isEmpty()) {
            return null;
        }
        try {
            String regex = glob
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".");
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            log.warn("Invalid glob pattern: {}", glob);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    /** 绑定Functions */
    private void bindFunctions() {
        if (openEventLog == null) {
            synchronized (this) {
                if (openEventLog == null) {
                    openEventLog = registry.require("OpenEventLogW",
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    readEventLog = registry.require("ReadEventLogW",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    closeEventLog = registry.require("CloseEventLog",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                    getNumberOfEventLogRecords = registry.require("GetNumberOfEventLogRecords",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                }
            }
        }
    }

    /**
     * coerce转为内存segment
     *
     * @param handleObj 处理obj
     * @return coerce转为内存segment的结果
     */
    private static MemorySegment coerceToMemorySegment(Object handleObj) {
        if (handleObj instanceof MemorySegment seg) {
            return seg;
        }
        if (handleObj instanceof Number num) {
            return MemorySegment.ofAddress(num.longValue());
        }
        return null;
    }

    /**
     * 获取最后一个记录错误
     *
     * @return 获取最后一个错误的结果
     */
    private int getLastError() {
        if (getLastErrorHandle == null) {
            return 0;
        }
        try {
            return (int) getLastErrorHandle.invokeExact();
        } catch (Throwable e) {
            log.trace("GetLastError via FFM failed", e);
            return 0;
        }
    }
}

