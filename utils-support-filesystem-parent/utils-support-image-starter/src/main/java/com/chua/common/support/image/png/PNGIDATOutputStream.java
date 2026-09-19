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
 * PNG IDAT chunk（图像数据块）写入流，写入时同步执行 zlib deflate 压缩。
 *
 * @author CH
 * @since 4.0.0.42
 */
final class PNGIDATOutputStream extends PNGImageOutputStream {

    /** Chunk类型 */
    private static final byte[] chunkType = {
        (byte)'I', (byte)'D', (byte)'A', (byte)'T'
    };

    /**
     * 构造方法，创建 PNGIDATOutput流 实例。
     *
     * @param stream 流，不允许为 null
     * @param chunkLength 分块长度，不允许为 null
     * @param deflaterLevel deflater级别，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    PNGIDATOutputStream(ImageOutputStream stream, int chunkLength, int deflaterLevel) throws IOException {
        super(stream, chunkLength, deflaterLevel);

        startChunk();
    }

    @Override
    /** 开始Chunk */
    protected void startChunk() throws IOException {
        crc.reset();
        this.startPos = stream.getStreamPosition();
 // 长度, will backpatch
        // (-1);
        stream.writeInt(-1);

        crc.update(chunkType, 0, 4);
        stream.write(chunkType, 0, 4);

        this.bytesRemaining = chunkLength;
    }

}

