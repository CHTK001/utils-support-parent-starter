package com.chua.common.support.lang.process.wrapped;

import com.chua.common.support.lang.process.ProgressBar;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;

/**
 * 一个由进度条跟踪进度的读取器。
 * <p>
 * 该类继承自 FilterReader，包装了一个底层的 Reader 和一个 ProgressBar 实例。
 * 当底层 Reader 进行读取操作时，会自动更新进度条的状态。
 * 同时支持 mark/reset 操作，能够回滚进度到标记点。
 *
 * @since 0.9.2
 * @author CH
 */
public class ProgressBarWrappedReader extends FilterReader {

    /**
     * 用于跟踪进度的进度条实例。
     */
    private final ProgressBar pb;

    /**
     * 在调用 mark 方法时记录的当前进度值，用于 reset 时恢复。
     */
    private long mark = 0;

    /**
     * 构造一个新的 ProgressBarWrappedReader。
     *
     * @param in 被包装的底层 Reader
     * @param pb 用于跟踪进度的 ProgressBar
     */
    public ProgressBarWrappedReader(Reader in, ProgressBar pb) {
        super(in);
        this.pb = pb;
    }

    /**
     * 获取当前关联的进度条实例。
     *
     * @return 进度条实例
     */
    public ProgressBar getProgressBar() {
        return pb;
    }

    /**
     * 读取单个字符。
     * 如果成功读取到字符，则进度条步进一次。
     *
     * @return 读取到的字符，如果到达流末尾则返回 -1
     * @throws IOException 如果发生 I/O 错误
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
     * 将字符读入字符数组。
     * 如果成功读取到字符，则进度条根据读取的数量步进。
     *
     * @param b 用于存储读取字符的缓冲区
     * @return 实际读取的字符数，如果到达流末尾则返回 -1
     * @throws IOException 如果发生 I/O 错误
     */
    @Override
    public int read(char[] b) throws IOException {
        int r = in.read(b);
        if (r != -1) {
            pb.stepBy(r);
        }
        return r;
    }

    /**
     * 将字符读入字符数组的指定区域。
     * 如果成功读取到字符，则进度条根据读取的数量步进。
     *
     * @param b 用于存储读取字符的缓冲区
     * @param off 缓冲区中开始存储字符的偏移量
     * @param len 要读取的最大字符数
     * @return 实际读取的字符数，如果到达流末尾则返回 -1
     * @throws IOException 如果发生 I/O 错误
     */
    @Override
    public int read(char[] b, int off, int len) throws IOException {
        int r = in.read(b, off, len);
        if (r != -1) {
            pb.stepBy(r);
        }
        return r;
    }

    /**
     * 跳过字符。
     * 跳过的字符数会被计入进度条。
     *
     * @param n 希望跳过的字符数
     * @return 实际跳过的字符数
     * @throws IOException 如果发生 I/O 错误
     */
    @Override
    public long skip(long n) throws IOException {
        long r = in.skip(n);
        pb.stepBy(r);
        return r;
    }

    /**
     * 标记当前读取位置。
     * 记录当前的进度值以便后续 reset 使用。
     *
     * @param readAheadLimit 允许读取-ahead 的最大字节数（此实现中未直接使用）
     * @throws IOException 如果发生 I/O 错误
     */
    @Override
    public void mark(int readAheadLimit) throws IOException {
        in.mark(readAheadLimit);
        mark = pb.getCurrent();
    }

    /**
     * 重置读取位置到上次标记的位置。
     * 将进度条回退到标记时的进度值。
     *
     * @throws IOException 如果发生 I/O 错误或该 Reader 不支持 reset
     */
    @Override
    public void reset() throws IOException {
        in.reset();
        pb.stepTo(mark);
    }

    /**
     * 关闭此读取器。
     * 同时关闭底层的 Reader 和进度条。
     *
     * @throws IOException 如果发生 I/O 错误
     */
    @Override
    public void close() throws IOException {
        in.close();
        pb.close();
    }
}