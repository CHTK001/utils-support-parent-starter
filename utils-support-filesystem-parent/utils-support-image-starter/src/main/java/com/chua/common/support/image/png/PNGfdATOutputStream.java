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
 * PNG fdAT chunk（APNG 帧数据块）写入流，在 chunk header 后附加 4 字节大端序号。
 *
 * @author CH
 * @since 4.0.0.42
 */
final class PNGfdATOutputStream extends PNGImageOutputStream {

    private static final byte[] chunkType = {
            (byte)'f', (byte)'d', (byte)'A', (byte)'T'
    };

    public int sequenceNumber;
    private final byte[] sequenceNumberBuf = new byte[4];

    PNGfdATOutputStream(ImageOutputStream stream, int chunkLength, int deflaterLevel, int sequenceNumber) throws IOException {
        super(stream, chunkLength, deflaterLevel);
        this.sequenceNumber = sequenceNumber;

        startChunk();
    }

    @Override
    protected void startChunk() throws IOException {
        crc.reset();
        this.startPos = stream.getStreamPosition();
        // length, will backpatch
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

