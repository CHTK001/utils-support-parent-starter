package com.chua.common.support.io.file.stream;

import java.io.IOException;
import java.io.InputStream;

/**
 * 归档输入流接口。
 *
 * <p>定义归档文件读取的抽象，提供获取下一个归档条目和读取字节的方法。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ArchiveInputStream {

    /**
     * 获取下一个归档条目。
     *
     * @return 下一个归档条目，没有更多条目时返回 null
     * @throws IOException IO 异常
     */
    ArchiveEntry getNextEntry() throws IOException;

    /**
     * 读取一个字节。
     *
     * @return 读取的字节，-1 表示结束
     * @throws IOException IO 异常
     */
    int read() throws IOException;

    /**
     * 读取字节到缓冲区。
     *
     * @param b 缓冲区
     * @return 实际读取字节数，-1 表示结束
     * @throws IOException IO 异常
     */
    int read(byte[] b) throws IOException;

    /**
     * 读取字节到缓冲区指定位置。
     *
     * @param b   缓冲区
     * @param off 起始偏移
     * @param len 最大读取长度
     * @return 实际读取字节数，-1 表示结束
     * @throws IOException IO 异常
     */
    int read(byte[] b, int off, int len) throws IOException;

    /**
     * 跳过指定字节数。
     *
     * @param n 要跳过的字节数
     * @return 实际跳过的字节数
     * @throws IOException IO 异常
     */
    long skip(long n) throws IOException;

    /**
     * 获取可读取字节数的估计值。
     *
     * @return 可读取字节数
     * @throws IOException IO 异常
     */
    int available() throws IOException;

    /**
     * 标记当前位置。
     *
     * @param readlimit 最大可回溯字节数
     */
    void mark(int readlimit);

    /**
     * 重置到标记位置。
     *
     * @throws IOException IO 异常
     */
    void reset() throws IOException;

    /**
     * 判断是否支持标记/重置。
     *
     * @return true 表示支持
     */
    boolean markSupported();

    /**
     * 关闭流。
     *
     * @throws IOException IO 异常
     */
    void close() throws IOException;
}