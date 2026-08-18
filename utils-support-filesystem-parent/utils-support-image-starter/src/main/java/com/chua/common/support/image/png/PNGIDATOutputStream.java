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

    PNGIDATOutputStream(ImageOutputStream stream, int chunkLength, int deflaterLevel) throws IOException {
        super(stream, chunkLength, deflaterLevel);

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

        this.bytesRemaining = chunkLength;
    }

}

