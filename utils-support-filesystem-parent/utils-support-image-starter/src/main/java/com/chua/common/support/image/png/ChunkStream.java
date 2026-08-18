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
 * PNG Chunk 写入流：在写入数据时同步累积 CRC，结束时回写 length 与 CRC 字段。
 * <p>仅作为 ImageOutputStream 的装饰使用，读操作被禁用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class ChunkStream extends ImageOutputStreamImpl {

    /** 流 */
    private final ImageOutputStream stream;
    /** 开始POS */
    private final long startPos;
    /** CRC */
    private final CRC crc = new CRC();

    ChunkStream(int type, ImageOutputStream stream) throws IOException {
        this.stream = stream;
        this.startPos = stream.getStreamPosition();

        // length, will backpatch
        // (-1);
        stream.writeInt(-1);

        writeInt(type);
    }

    @Override
    public int read() throws IOException {
        throw new RuntimeException("Method not available");
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        throw new RuntimeException("Method not available");
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        crc.update(b, off, len);
        stream.write(b, off, len);
    }

    @Override
    public void write(int b) throws IOException {
        crc.update(b);
        stream.write(b);
    }

    void finish() throws IOException {
        // Write CRC
        stream.writeInt(crc.getValue());

        // Write length
        long pos = stream.getStreamPosition();
        stream.seek(startPos);
        stream.writeInt((int)(pos - startPos) - 12);

        // Return to end of chunk and flush to minimize buffering
        stream.seek(pos);
        stream.flushBefore(pos);
    }

}

