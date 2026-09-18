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
* PNG 图像数据流写入抽象基类，使用 {@link Deflater} 实时压缩并在 chunk 边界收尾。
* <p>子类需实现 {@link #startChunk()}，决定写入哪种 chunk 类型（IDAT / fdAT）。</p>
*
* @author CH
* @since 4.0.0.42
 */
abstract class PNGImageOutputStream extends ImageOutputStreamImpl {

    /** 流 */
    protected ImageOutputStream stream;
    /** 开始采购订单 */
    protected long startPos;
    /** Chunk长度 */
    protected final int chunkLength;
    /** CRC */
    protected final CRC crc = new CRC();

    /** DEF */
    private final Deflater def;
    /** BUF */
    private final byte[] buf = new byte[512];
    // reused 1 byte[] array:
    /** Wbuf1 */
    private final byte[] wbuf1 = new byte[1];

    /** Bytes剩余 */
    protected int bytesRemaining;

    PNGImageOutputStream(ImageOutputStream stream, int chunkLength,
                         int deflaterLevel) throws IOException {
        this.stream = stream;
        this.chunkLength = chunkLength;
        this.def = new Deflater(deflaterLevel);

 // 启动 chunk later
        //startChunk();
    }

    /** 开始Chunk */
    protected abstract void startChunk() throws IOException;

    /** 饰面chunk */
    protected void finishChunk() throws IOException {
 // 写入 CRC
        stream.writeInt(crc.getValue());

 // 写入 长度
        long pos = stream.getStreamPosition();
        stream.seek(startPos);
        stream.writeInt((int)(pos - startPos) - 12);

 // 返回 转为 结束 的 chunk 和 flush 转为 最小化 缓冲
        stream.seek(pos);
        try {
            stream.flushBefore(pos);
        } catch (IOException e) {
            /*
    * If flush之前() 失败 we 尝试 转为 access 启动采购订单 入 最终
    * block 的 写入_IDAT(). We should 更新 启动采购订单 转为 avoid
    * 索引出的bound异常 while seek() 是否 happening.
    */
            this.startPos = stream.getStreamPosition();
            throw e;
        }
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
        if (len == 0) {
            return;
        }

        if (!def.finished()) {
            def.setInput(b, off, len);
            while (!def.needsInput()) {
                deflate();
            }
        }
    }

    /**
     * deflate。
     *
     * @throws IOException 当执行过程不满足前置条件时
     */
    void deflate() throws IOException {
        int len = def.deflate(buf, 0, buf.length);
        int off = 0;

        while (len > 0) {
            if (bytesRemaining == 0) {
                finishChunk();
                startChunk();
            }

            int nbytes = Math.min(len, bytesRemaining);
            crc.update(buf, off, nbytes);
            stream.write(buf, off, nbytes);

            off += nbytes;
            len -= nbytes;
            bytesRemaining -= nbytes;
        }
    }

    @Override
    /** 写入 */
    public void write(int b) throws IOException {
        wbuf1[0] = (byte)b;
        write(wbuf1, 0, 1);
    }

    /**
     * 完成。
     *
     * @throws IOException 当执行过程不满足前置条件时
     */
    void finish() throws IOException {
        try {
            if (!def.finished()) {
                def.finish();
                while (!def.finished()) {
                    deflate();
                }
            }
            finishChunk();
        } finally {
            def.end();
        }
    }

}

