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

final class ChunkStream extends ImageOutputStreamImpl {

    private final ImageOutputStream stream;
    private final long startPos;
    private final CRC crc = new CRC();

    ChunkStream(int type, ImageOutputStream stream) throws IOException {
        this.stream = stream;
        this.startPos = stream.getStreamPosition();

        // (-1); // length, will backpatch
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

