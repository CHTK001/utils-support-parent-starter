package com.chua.common.support.lang.process.wrapped;

import com.chua.common.support.lang.process.ProgressBar;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * 一个由进度条跟踪进度的 Writer。
 * <p>
 * 该类继承自 FilterWriter，在写入数据时自动更新关联的 ProgressBar。
 * 它支持字符、字符数组和字符串的写入操作，并在每次写入或刷新时更新进度。
 * </p>
 *
 * @since 0.9.3
 * @author CH
 */
public class ProgressBarWrappedWriter extends FilterWriter {

    /**
     * 用于跟踪进度的进度条实例。
     */
    private final ProgressBar pb;

    /**
     * 构造一个新的 ProgressBarWrappedWriter。
     *
     * @param out 被包装的底层 Writer。
     * @param pb  用于跟踪进度的 ProgressBar。
     */
    public ProgressBarWrappedWriter(Writer out, ProgressBar pb) {
        super(out);
        this.pb = pb;
    }

    /**
     * 写入单个字符并更新进度。
     *
     * @param c 要写入的字符。
     * @throws IOException 如果发生 I/O 错误。
     */
    @Override
    public void write(int c) throws IOException {
        if (c != -1) {
            out.write(c);
            pb.step();
        }
    }

    /**
     * 写入字符数组的一部分并更新进度。
     *
     * @param cbuf 字符缓冲区。
     * @param off  起始偏移量。
     * @param len  要写入的长度。
     * @throws IOException 如果发生 I/O 错误。
     */
    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        if (len > 0) {
            out.write(cbuf, off, len);
            pb.stepBy(len);
        }
    }

    /**
     * 写入字符串的一部分并更新进度。
     *
     * @param str 要写入的字符串。
     * @param off 起始偏移量。
     * @param len 要写入的长度。
     * @throws IOException 如果发生 I/O 错误。
     */
    @Override
    public void write(String str, int off, int len) throws IOException {
        if (len > 0) {
            out.write(str, off, len);
            pb.stepBy(len);
        }
    }

    /**
     * 写入整个字符串并更新进度。
     *
     * @param str 要写入的字符串。
     * @throws IOException 如果发生 I/O 错误。
     */
    @Override
    public void write(String str) throws IOException {
        if (str != null && !str.isEmpty()) {
            out.write(str);
            pb.stepBy(str.length());
        }
    }

    /**
     * 刷新底层流并刷新进度条。
     *
     * @throws IOException 如果发生 I/O 错误。
     */
    @Override
    public void flush() throws IOException {
        out.flush();
        pb.refresh();
    }

    /**
     * 关闭底层流并关闭进度条。
     *
     * @throws IOException 如果发生 I/O 错误。
     */
    @Override
    public void close() throws IOException {
        out.close();
        pb.close();
    }

}