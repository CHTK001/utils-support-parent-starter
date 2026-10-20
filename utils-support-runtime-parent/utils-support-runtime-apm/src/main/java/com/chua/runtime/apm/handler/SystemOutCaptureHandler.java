package com.chua.runtime.apm.handler;

import com.chua.runtime.plugin.Plugin;
import com.chua.runtime.plugin.PluginContext;
import lombok.Data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 系统输出捕获处理器 — 重定向 {@link System#out} / {@link System#err} 到 APM 日志流。
 *
 * <p>应用直接使用 {@code System.out.println()} / {@code printStackTrace()} 时，
 * 普通 LogHandler 无法感知。该处理器通过 {@link System#setOut} 包装流，
 * 按行拆分并记录到有界环形缓冲，供「实时日志」页面查看。</p>
 *
 * <p>同时把 System.err 按 ERROR 级别记录，便于定位异常堆栈。</p>
 *
 * @author CH
 * @since 4.1.0
 */
public class SystemOutCaptureHandler implements Plugin {

    private static final Logger LOG = Logger.getLogger(SystemOutCaptureHandler.class.getName());

    /**
     * 处理器名称。
     */
    private static final String HANDLER_NAME = "system-out-capture-handler";

    /**
     * 处理器版本。
     */
    private static final String HANDLER_VERSION = "1.0.0";

    /**
     * 最大保留条数。
     */
    private static final int MAX_ENTRIES = 2000;

    /**
     * 是否启用。
     */
    private boolean enabled;

    /**
     * 是否已启动。
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 环形缓冲。
     */
    private final ConcurrentLinkedDeque<SystemOutEntry> entries = new ConcurrentLinkedDeque<>();

    /**
     * 原始 stdout（恢复用）。
     */
    private PrintStream originalOut;

    /**
     * 原始 stderr（恢复用）。
     */
    private PrintStream originalErr;

    /**
     * 无参构造。
     */
    public SystemOutCaptureHandler() {
    }

    @Override
    public String name() {
        return HANDLER_NAME;
    }

    @Override
    public String version() {
        return HANDLER_VERSION;
    }

    @Override
    public void init(PluginContext context) throws Exception {
        this.enabled = "true".equals(context.getProperty("system.out.enabled", "true"));
        LOG.log(Level.INFO, String.format("SystemOutCaptureHandler 初始化完成，启用状态: %s", enabled));
    }

    @Override
    public void start() throws Exception {
        if (!enabled) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        originalOut = System.out;
        originalErr = System.err;
        try {
            System.setOut(new CaptureStream(originalOut, "STDOUT"));
            System.setErr(new CaptureStream(originalErr, "STDERR"));
            LOG.log(Level.INFO, "System.out / System.err 已重定向到 APM 日志流");
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "System.out 重定向失败: " + t.getMessage());
            restore();
        }
    }

    @Override
    public void stop() throws Exception {
        this.enabled = false;
        if (started.compareAndSet(true, false)) {
            restore();
            LOG.log(Level.INFO, "SystemOutCaptureHandler 停止，已恢复原始输出流");
        }
    }

    /**
     * 恢复原始输出流。
     */
    private void restore() {
        try {
            if (originalOut != null) {
                System.setOut(originalOut);
            }
            if (originalErr != null) {
                System.setErr(originalErr);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "恢复 System.out 失败: " + e.getMessage());
        }
    }

    @Override
    public String status() {
        return String.format("SystemOutCaptureHandler[enabled=%s, entries=%d]", enabled, entries.size());
    }

    @Override
    public boolean isRunning() {
        return enabled && started.get();
    }

    /**
     * 获取最近 N 条系统输出。
     *
     * @param limit 条数上限
     * @return 输出条目
     */
    public List<SystemOutEntry> tail(int limit) {
        List<SystemOutEntry> result = new ArrayList<>(entries);
        if (result.size() > limit) {
            result = new ArrayList<>(result.subList(result.size() - limit, result.size()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取全部系统输出。
     *
     * @return 输出条目
     */
    public List<SystemOutEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * 清空缓冲。
     */
    public void clear() {
        entries.clear();
    }

    /**
     * 包装输出流：按行拆分写入缓冲。
     */
    private final class CaptureStream extends PrintStream {

        private final String source;

        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

        private CaptureStream(PrintStream original, String source) {
            super(new ByteArrayOutputStream());
            this.source = source;
        }

        @Override
        public void write(int b) {
            synchronized (lineBuffer) {
                lineBuffer.write(b);
                flushLineIfNeeded();
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            synchronized (lineBuffer) {
                lineBuffer.write(buf, off, len);
                flushLineIfNeeded();
            }
        }

        @Override
        public void println(String x) {
            writeLine(x == null ? "null" : x);
        }

        @Override
        public void println(Object x) {
            writeLine(String.valueOf(x));
        }

        private void writeLine(String line) {
            addEntry(line);
        }

        private void flushLineIfNeeded() {
            byte[] bytes = lineBuffer.toByteArray();
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (text.contains("\n")) {
                int lastNl = text.lastIndexOf('\n');
                String complete = text.substring(0, lastNl);
                if (!complete.isEmpty()) {
                    addEntry(complete);
                }
                if (lastNl + 1 < text.length()) {
                    lineBuffer.reset();
                    lineBuffer.writeBytes(text.substring(lastNl + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    lineBuffer.reset();
                }
            }
        }

        private void addEntry(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            SystemOutEntry entry = new SystemOutEntry();
            entry.setTimestamp(System.currentTimeMillis());
            entry.setSource(source);
            entry.setLevel("STDERR".equals(source) ? "ERROR" : "INFO");
            entry.setLine(text);
            entries.addLast(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.pollFirst();
            }
        }
    }

    /**
     * 系统输出条目。
     */
    @Data
    public static class SystemOutEntry {
        private long timestamp;
        private String source;
        private String level;
        private String line;
    }
}
