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

final class PNGIDATOutputStream extends PNGImageOutputStream {

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
        // (-1); // length, will backpatch
        stream.writeInt(-1);

        crc.update(chunkType, 0, 4);
        stream.write(chunkType, 0, 4);

        this.bytesRemaining = chunkLength;
    }

}

