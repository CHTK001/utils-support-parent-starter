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
 * PNG fdat chunk（APNG 帧数据块）写入流，在 chunk 头部 后附加 4 字节大端序号。
 *
 * @author CH
 * @since 4.0.0.42
 */
final class PNGfdATOutputStream extends PNGImageOutputStream {

    /**
     * Chunk类型
    */
    private static final byte[] chunkType = {
            (byte)'f', (byte)'d', (byte)'A', (byte)'T'
    };

    /**
     * Sequence数字
    */
    public int sequenceNumber;
    /**
     * Sequence数字BUF
    */
    private final byte[] sequenceNumberBuf = new byte[4];

    /**
     * 构造方法，创建 PNGfdATOutput流 实例。
     *
     * @param stream 流，不允许为 null
     * @param chunkLength 分块长度，不允许为 null
     * @param deflaterLevel deflater级别，不允许为 null
     * @param sequenceNumber 方法入参 sequenceNumber
     * @throws IOException 当执行过程不满足前置条件时
     */
    PNGfdATOutputStream(ImageOutputStream stream, int chunkLength, int deflaterLevel, int sequenceNumber) throws IOException {
        super(stream, chunkLength, deflaterLevel);
        this.sequenceNumber = sequenceNumber;

        startChunk();
    }

    @Override
    /**
     * 开始Chunk
    */
    protected void startChunk() throws IOException {
        crc.reset();
        this.startPos = stream.getStreamPosition();
 // 长度, will backpatch
        // (-1);
        stream.writeInt(-1);

        crc.update(chunkType, 0, 4);
        stream.write(chunkType, 0, 4);

        sequenceNumberBuf[0] = (byte)(sequenceNumber >>> 24);
        sequenceNumberBuf[1] = (byte)(sequenceNumber >>> 16);
        sequenceNumberBuf[2] = (byte)(sequenceNumber >>>  8);
        sequenceNumberBuf[3] = (byte)(sequenceNumber >>>  0);

        crc.update(sequenceNumberBuf, 0, 4);
        stream.writeInt(sequenceNumber);
        sequenceNumber ++;

        this.bytesRemaining = chunkLength;
    }

}

