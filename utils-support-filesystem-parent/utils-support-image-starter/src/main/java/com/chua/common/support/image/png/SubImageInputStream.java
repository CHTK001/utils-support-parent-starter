package com.chua.common.support.image.png;

import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageInputStreamImpl;
import java.io.IOException;

/**
 * SubImageInputStream 类继承自 ImageInputStreamImpl，从现有的 ImageInputStream 中
 * 读取一个子图像流。它维护了关于子图像流的起始位置和长度的信息，以便能够正确地
 * 从父图像流中读取子图像数据。
 *
 * @author CH
*/
final class SubImageInputStream extends ImageInputStreamImpl {

    // 父图像流
    ImageInputStream stream;
    // 子图像流的起始位置
    long startingPos;
    // 子图像流的初始长度
    int startingLength;
    // 子图像流的剩余长度
    int length;

    /**
     * 构造函数，初始化 SubImageInputStream。
     *
     * @param stream 父图像流
     * @param length 子图像流的长度
     * @throws IOException 如果在初始化过程中发生 I/O 错误
     */
    public SubImageInputStream(ImageInputStream stream, int length)
        throws IOException {
        this.stream = stream;
        this.startingPos = stream.getStreamPosition();
        this.startingLength = this.length = length;
    }

    /**
     * 从子图像流中读取一个字节。
     *
     * @return 读取的字节，如果达到子图像流的末尾则返回 -1
     * @throws IOException 如果在读取过程中发生 I/O 错误
     */
    public int read() throws IOException {
        if (length <= 0) {
            return -1;
        } else {
            --length;
            return stream.read();
        }
    }

    /**
     * 从子图像流中读取一组字节到字节数组中。
     *
     * @param b   目标字节数组
     * @param off 起始偏移量
     * @param len 要读取的最大字节数
     * @return 实际读取的字节数，如果达到子图像流的末尾则返回 -1
     * @throws IOException 如果在读取过程中发生 I/O 错误
     */
    public int read(byte[] b, int off, int len) throws IOException {
        if (length <= 0) {
            return -1;
        }
        len = Math.min(len, length);
        int bytes = stream.read(b, off, len);
        length -= bytes;
        return bytes;
    }

    /**
     * 返回子图像流的长度。
     *
     * @return 子图像流的长度
     */
    public long length() {
        return startingLength;
    }

    /**
     * 设置子图像流的位置。
     *
     * @param pos 子图像流中的新位置
     * @throws IOException 如果在设置位置过程中发生 I/O 错误
     */
    public void seek(long pos) throws IOException {
        stream.seek(pos - startingPos);
        streamPos = pos;
    }

}
