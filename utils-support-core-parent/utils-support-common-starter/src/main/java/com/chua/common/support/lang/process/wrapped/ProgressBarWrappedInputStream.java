package com.chua.common.support.lang.process.wrapped;

import com.chua.common.support.lang.process.ProgressBar;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 任何输入流，其进度由进度条跟踪。
 * @author CH
 * @since 0.7.0
 */
public class ProgressBarWrappedInputStream extends FilterInputStream {

    /**
     * 进度条实例，用于跟踪读取进度。
     */
    private final ProgressBar pb;

    /**
     * 标记位置时的已读取字节数，用于 reset 操作恢复进度。
     */
    private long mark = 0;

    /**
     * 构造一个带有进度条跟踪的输入流包装器。
     *
     * @param in 被包装的原始输入流
     * @param pb 用于跟踪进度的进度条实例
     */
    public ProgressBarWrappedInputStream(InputStream in, ProgressBar pb) {
        super(in);
        this.pb = pb;
    }

    /**
     * 获取关联的进度条实例。
     *
     * @return 进度条实例
     */
    public ProgressBar getProgressBar() {
        return pb;
    }

    /**
     * 读取单个字节的数据。
     *
     * @return 读取到的字节值，如果到达流末尾则返回 -1
     * @throws IOException 当发生 I/O 错误时抛出
     */
    @Override
    public int read() throws IOException {
        int r = in.read();
        if (r != -1) {
            pb.step();
        }
        return r;
    }

    /**
     * 读取字节数组中的数据。
     *
     * @param b 目标字节数组
     * @return 实际读取的字节数，如果到达流末尾则返回 -1
     * @throws IOException 当发生 I/O 错误时抛出
     */
    @Override
    public int read(byte[] b) throws IOException {
        int r = in.read(b);
        if (r != -1) {
            pb.stepBy(r);
        }
        return r;
    }

    /**
     * 从指定偏移量开始，读取指定长度的字节数组数据。
     *
     * @param b   目标字节数组
     * @param off 起始偏移量
     * @param len 要读取的最大长度
     * @return 实际读取的字节数，如果到达流末尾则返回 -1
     * @throws IOException 当发生 I/O 错误时抛出
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int r = in.read(b, off, len);
        if (r != -1) {
            pb.stepBy(r);
        }
        return r;
    }

    /**
     * 跳过并丢弃输入流中的 n 个字节。
     *
     * @param n 要跳过的字节数
     * @return 实际跳过的字节数
     * @throws IOException 当发生 I/O 错误时抛出
     */
    @Override
    public long skip(long n) throws IOException {
        long r = in.skip(n);
        pb.stepBy(r);
        return r;
    }

    /**
     * 在流上设置标记。
     *
     * @param readLimit 允许读取而不使标记失效的最大字节数
     */
    @Override
    public void mark(int readLimit) {
        in.mark(readLimit);
        mark = pb.getCurrent();
    }

    /**
     * 重置流到上次标记的位置。
     *
     * @throws IOException 如果流不支持标记或重置失败时抛出
     */
    @Override
    public void reset() throws IOException {
        in.reset();
        pb.stepTo(mark);
    }

    /**
     * 关闭此输入流及其关联的进度条。
     *
     * @throws IOException 当发生 I/O 错误时抛出
     */
    @Override
    public void close() throws IOException {
        in.close();
        pb.close();
    }

}