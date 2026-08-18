package com.chua.log.support.file.impl;

import com.chua.common.support.file.builder.WriteCallback;
import com.chua.common.support.file.builder.WriteBuilder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 日志文件写入构建器。
 *
 * <p>支持以下写入模式：</p>
 * <ul>
 *     <li><b>追加模式</b> — 在文件末尾追加内容（默认）</li>
 *     <li><b>覆盖模式</b> — 覆盖文件全部内容</li>
 *     <li><b>时间戳前缀</b> — 自动为每行添加 {@code [yyyy-MM-dd HH:mm:ss]} 前缀</li>
 *     <li><b>延迟写入</b> — 多次 {@link #write(Object)} 后调用 {@link #finish()} 批量写入</li>
 *     <li><b>行级别回调</b> — 通过 {@link #withCallback(WriteCallback)} 监控写入进度</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * LogWriteBuilder wb = (LogWriteBuilder) logFs.write(new File("app.log"));
 *
 * // 追加模式 + 时间戳 + 多条写入
 * wb.append()
 *   .withTimestamp(true)
 *   .write("系统启动成功")
 *   .write("用户登录: userId=1001")
 *   .write(Map.of("level", "ERROR", "message", "连接超时"))
 *   .finish();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LogWriteBuilder extends WriteBuilder {

    /** 默认时间戳格式 */
    /** Default_timestamp_fmt */
    private static final DateTimeFormatter DEFAULT_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 是否追加模式（默认 true） */
    /** Append模式 */
    private boolean appendMode = true;

    /** 是否自动添加时间戳前缀（默认 false） */
    /** With时间戳 */
    private boolean withTimestamp;

    /** 自定义时间戳格式 */
    /** 时间戳formatter */
    private DateTimeFormatter timestampFormatter = DEFAULT_TIMESTAMP_FMT;

    /** 行前缀（自定义固定前缀，时间戳之前） */
    /** Prefix */
    private String prefix;

    /** 行后缀（追加在行尾） */
    /** Suffix */
    private String suffix;

    /** 换行符 */
    /** Lineseparator */
    private String lineSeparator = System.lineSeparator();

    /**
     * 构造日志写入构建器。
     *
     * @param file 目标日志文件
     */
    public LogWriteBuilder(File file) {
        super(file);
    }

    // ==================== 链式配置 ====================

    /**
     * 设置为追加模式（写入到文件末尾）。
     *
     * @return 当前构建器
     */
    public LogWriteBuilder append() {
        this.appendMode = true;
        return this;
    }

    /**
     * 设置为覆盖模式（从头写入）。
     *
     * @return 当前构建器
     */
    public LogWriteBuilder overwrite() {
        this.appendMode = false;
        return this;
    }

    /**
     * 设置是否自动添加时间戳前缀。
     *
     * @param enabled 是否启用
     * @return 当前构建器
     */
    public LogWriteBuilder withTimestamp(boolean enabled) {
        this.withTimestamp = enabled;
        return this;
    }

    /**
     * 设置自定义时间戳格式。
     *
     * @param pattern {@link DateTimeFormatter} 格式
     * @return 当前构建器
     */
    public LogWriteBuilder withTimestampFormat(String pattern) {
        this.timestampFormatter = DateTimeFormatter.ofPattern(pattern);
        return this;
    }

    /**
     * 设置行前缀（时间戳之前）。
     *
     * @param prefix 前缀字符串
     * @return 当前构建器
     */
    public LogWriteBuilder withPrefix(String prefix) {
        this.prefix = prefix;
        return this;
    }

    /**
     * 设置行后缀（追加在行尾）。
     *
     * @param suffix 后缀字符串
     * @return 当前构建器
     */
    public LogWriteBuilder withSuffix(String suffix) {
        this.suffix = suffix;
        return this;
    }

    /**
     * 设置自定义换行符。
     *
     * @param lineSeparator 换行符
     * @return 当前构建器
     */
    public LogWriteBuilder withLineSeparator(String lineSeparator) {
        this.lineSeparator = lineSeparator;
        return this;
    }

    @Override
    public LogWriteBuilder withCharset(String charset) {
        super.withCharset(charset);
        return this;
    }

    // ==================== 写入方法 ====================

    @Override
    public LogWriteBuilder write(Object data) {
        pending.add(data);
        return this;
    }

    /**
     * 追加写入单条文本。
     *
     * @param line 文本行
     * @return 当前构建器
     */
    public LogWriteBuilder write(String line) {
        pending.add(line);
        return this;
    }

    /**
     * 追加写入多条文本。
     *
     * @param lines 文本行列表
     * @return 当前构建器
     */
    public LogWriteBuilder write(List<String> lines) {
        pending.addAll(lines);
        return this;
    }

    /**
     * 立即写入单条文本（不入队列，直接写入文件）。
     *
     * @param line 文本行
     */
    public void writeAndFlush(String line) {
        try (BufferedWriter writer = createWriter()) {
            writer.write(formatLine(line));
            writer.write(lineSeparator);
        } catch (IOException e) {
            if (callback != null) {
                callback.onComplete(false);
            }
            throw new RuntimeException("日志写入失败: " + file, e);
        }
    }

    /**
     * 立即写入多条文本（不入队列，直接写入文件）。
     *
     * @param lines 文本行列表
     */
    public void writeAndFlush(List<String> lines) {
        try (BufferedWriter writer = createWriter()) {
            for (String line : lines) {
                writer.write(formatLine(line));
                writer.write(lineSeparator);
            }
        } catch (IOException e) {
            if (callback != null) {
                callback.onComplete(false);
            }
            throw new RuntimeException("日志写入失败: " + file, e);
        }
    }

    @Override
    public void finish() {
        if (file == null) {
            return;
        }
        callback.onStart();
        callback.onBeginWrite();

        try (BufferedWriter writer = createWriter()) {
            long written = 0;
            for (Object entry : pending) {
                List<String> linesToWrite = resolveLines(entry);
                for (String line : linesToWrite) {
                    String formatted = formatLine(line);
                    writer.write(formatted);
                    writer.write(lineSeparator);
                    written += formatted.getBytes(charset).length + lineSeparator.getBytes(charset).length;
                }
            }
            callback.onProgress((int) written, (int) written);
            callback.onComplete(true);
        } catch (IOException e) {
            callback.onComplete(false);
            throw new RuntimeException("日志写入失败: " + file, e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 创建文件写入器（根据 appendMode 决定追加或覆盖）。
     */
    private BufferedWriter createWriter() throws IOException {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(file, appendMode), charset));
    }

    /**
     * 将 pending 中的条目解析为行列表。
     */
    @SuppressWarnings("unchecked")
    private List<String> resolveLines(Object entry) {
        List<String> result = new ArrayList<>();
        if (entry instanceof String s) {
            result.add(s);
        } else if (entry instanceof List) {
            for (Object item : (List<?>) entry) {
                if (item instanceof String) {
                    result.add((String) item);
                } else if (item != null) {
                    result.add(item.toString());
                }
            }
        } else if (entry instanceof Map) {
            result.add(entry.toString());
        } else if (entry != null) {
            result.add(entry.toString());
        }
        return result;
    }

    /**
     * 格式化单行：前缀 + 时间戳 + 内容 + 后缀。
     */
    private String formatLine(String line) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix);
        }
        if (withTimestamp) {
            sb.append('[').append(timestampFormatter.format(LocalDateTime.now())).append("] ");
        }
        sb.append(line);
        if (suffix != null) {
            sb.append(suffix);
        }
        return sb.toString();
    }
}
