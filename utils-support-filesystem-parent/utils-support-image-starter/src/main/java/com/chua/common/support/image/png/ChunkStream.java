package com.chua.common.support.image.png;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.ImageOutputStreamImpl;
import java.awt.*;
import java.awt.image.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * PNG Chunk 写入流：在写入数据时同步累积 CRC，结束时回写 长度 与 CRC 字段。
 * <p>仅作为 ImageOutputStream 的装饰使用，读操作被禁用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class ChunkStream extends ImageOutputStreamImpl {

    /** 流 */
    private final ImageOutputStream stream;
    /** 开始采购订单 */
    private final long startPos;
    /** CRC */
    private final CRC crc = new CRC();

    /**
     * 构造方法，创建 分块流 实例。
     *
     * @param type 类型，不允许为 null
     * @param stream 流，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    ChunkStream(int type, ImageOutputStream stream) throws IOException {
        this.stream = stream;
        this.startPos = stream.getStreamPosition();

 // 长度, will backpatch
        // (-1);
        stream.writeInt(-1);

        writeInt(type);
    }

    @Override
    /** 读取 */
    public int read() throws IOException {
        throw new RuntimeException("Method not available");
    }

    @Override
    /** 读取 */
    public int read(byte[] b, int off, int len) throws IOException {
        throw new RuntimeException("Method not available");
    }

    @Override
    /** 写入 */
    public void write(byte[] b, int off, int len) throws IOException {
        crc.update(b, off, len);
        stream.write(b, off, len);
    }

    @Override
    /** 写入 */
    public void write(int b) throws IOException {
        crc.update(b);
        stream.write(b);
    }

    /**
     * 完成。
     *
     * @throws IOException 当执行过程不满足前置条件时
     */
    void finish() throws IOException {
 // 写入 CRC
        stream.writeInt(crc.getValue());

 // 写入 长度
        long pos = stream.getStreamPosition();
        stream.seek(startPos);
        stream.writeInt((int)(pos - startPos) - 12);

 // 返回 转为 结束 的 chunk 和 flush 转为 最小化 缓冲
        stream.seek(pos);
        stream.flushBefore(pos);
    }

}

