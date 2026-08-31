package com.chua.image.support.heif;

import javax.imageio.ImageWriter;
import javax.imageio.IIOException;
import javax.imageio.ImageWriteParam;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * HEIC/HEIF 图像写入器（纯Java实现）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HEICImageWriter extends ImageWriter {

    private ImageOutputStream output;
    private boolean writing = false;

    public HEICImageWriter(HEICImageWriterSpi spi) {
        super(spi);
    }

    @Override
    public void setOutput(Object output) {
        this.output = (ImageOutputStream) output;
        this.writing = false;
    }

    @Override
    public void prepareWriteEmpty(IIOMetadata streamMetadata,
                                   IIOMetadata imageMetadata,
                                   ImageWriteParam param) throws IOException {
        throw new UnsupportedOperationException("Empty write not supported for HEIC");
    }

    @Override
    public void write(IIOMetadata metadata, javax.imageio.IIOImage image, ImageWriteParam param) throws IOException {
        if (output == null) throw new IOException("Output not set");
        BufferedImage img = image.getImage();
        if (img == null) throw new IOException("No image data");

        try {
            HeifNativeEncoder.encode(img, output);
        } catch (Exception e) {
            throw new IIOException("HEIC encode failed", e);
        }
    }

    @Override
    public void flush() {
        if (output != null) {
            try { output.flush(); } catch (IOException ignored) {}
        }
        this.writing = false;
    }

    @Override
    public void dispose() throws IOException {
        if (output != null) {
            try { output.close(); } catch (IOException ignored) {}
            this.output = null;
        }
        this.writing = false;
    }

    @Override
    public void close() throws IOException {
        dispose();
    }
}
