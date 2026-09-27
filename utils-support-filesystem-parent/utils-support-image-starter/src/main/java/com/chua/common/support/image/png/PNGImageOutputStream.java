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

    /**
     * 流
    */
    protected ImageOutputStream stream;
    /**
     * chunk 起始位置
    */
    protected long startPos;
    /**
     * Chunk长度
    */
    protected final int chunkLength;
    /**
     * CRC
    */
    protected final CRC crc = new CRC();

    /**
     * 压缩器
    */
    private final Deflater def;
    /**
     * 缓冲区
    */
    private final byte[] buf = new byte[512];
    // reused 1 byte[] array:
    /**
     * 单字节写入缓冲
    */
    private final byte[] wbuf1 = new byte[1];

    /**
     * 剩余待写字节数
    */
    protected int bytesRemaining;

    PNGImageOutputStream(ImageOutputStream stream, int chunkLength,
                         int deflaterLevel) throws IOException {
        this.stream = stream;
        this.chunkLength = chunkLength;
        this.def = new Deflater(deflaterLevel);

        // start chunk later
        //startChunk();
    }

    /**
     * 开始Chunk
    */
    protected abstract void startChunk() throws IOException;

    /**
     * 收尾 chunk
    */
    protected void finishChunk() throws IOException {
 // 写入 CRC
        stream.writeInt(crc.getValue());

        // 写入长度
        long pos = stream.getStreamPosition();
        stream.seek(startPos);
        stream.writeInt((int)(pos - startPos) - 12);

        // 返回 chunk 结束位置并尽量减少 flush 缓冲
        stream.seek(pos);
        try {
            stream.flushBefore(pos);
        } catch (IOException e) {
            /*
     * If flushBefore() fails we try to access the start position of the final
     * block by write_IDAT(). We should update the start position to avoid
     * an IndexOutOfBoundsException while seek() is happening.
    */
            this.startPos = stream.getStreamPosition();
            throw e;
        }
    }

    @Override
    /**
     * 读取
    */
    public int read() throws IOException {
        throw new RuntimeException("Method not available");
    }

    @Override
    /**
     * 读取
    */
    public int read(byte[] b, int off, int len) throws IOException {
        throw new RuntimeException("Method not available");
    }

    @Override
    /**
     * 写入
    */
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
    /**
     * 写入
    */
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

