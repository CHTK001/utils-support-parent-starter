package com.chua.common.support.lang.process.wrapped;

import com.chua.common.support.lang.process.ProgressBar;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nonnull;

/**
* 一个包装输出流，用于在写入数据时更新进度条。
*
* @author CH
* @since 4.0.0.42
 */
public class ProgressBarWrappedOutputStream extends FilterOutputStream {

    /**
    * 关联的进度条实例。
     */
    private final ProgressBar pb;

    /**
    * 构造函数，初始化包装流和进度条。
    *
    * @param out 被包装的基础输出流
    * @param pb  需要更新的进度条
     */
    public ProgressBarWrappedOutputStream(OutputStream out, ProgressBar pb) {
        super(out);
        this.pb = pb;
    }

    /**
    * 获取当前关联的进度条。
    *
    * @return 进度条实例
     */
    public ProgressBar getProgressBar() {
        return pb;
    }

    /**
    * 写入单个字节并更新进度条。
    *
    * @param b 要写入的字节
    * @throws IOException 如果发生I/O错误
     */
    @Override
    public void write(int b) throws IOException {
        if (out != null) {
            out.write(b);
        }
        pb.step();
    }

    /**
    * 写入字节数组并更新进度条。
    *
    * @param b 要写入的字节数组
    * @throws IOException 如果发生I/O错误
     */
    @Override
    public void write(byte[] b) throws IOException {
        if (b == null || b.length == 0) {
            return;
        }
        out.write(b, 0, b.length);
        pb.stepBy(b.length);
    }

    /**
    * 写入字节数组的一部分并更新进度条。
    *
    * @param b  要写入的字节数组
    * @param off 起始偏移量
    * @param len 要写入的长度
    * @throws IOException 如果发生I/O错误
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (b == null || off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return;
        }
        out.write(b, off, len);
        pb.stepBy(len);
    }

    /**
    * 刷新底层流并刷新进度条显示。
    *
    * @throws IOException 如果发生I/O错误
     */
    @Override
    public void flush() throws IOException {
        out.flush();
        pb.refresh();
    }

    /**
    * 关闭底层流并关闭进度条。
    *
    * @throws IOException 如果发生I/O错误
     */
    @Override
    public void close() throws IOException {
        out.close();
        pb.close();
    }
}
