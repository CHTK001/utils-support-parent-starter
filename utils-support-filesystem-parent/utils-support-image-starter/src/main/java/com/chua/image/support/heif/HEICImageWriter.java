package com.chua.image.support.heif;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.IIOException;
import javax.imageio.ImageWriteParam;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * HEIC/HEIF 图像写入器（纯Java实现）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HEICImageWriter extends ImageWriter {

    private ImageOutputStream output;

    public HEICImageWriter(HEICImageWriterSpi spi) {
        super(spi);
    }

    @Override
    public void setOutput(Object output) {
        this.output = (ImageOutputStream) output;
    }

    @Override
    public void prepareWriteEmpty(IIOMetadata streamMetadata,
                                   IIOMetadata imageMetadata,
                                   ImageWriteParam param) throws IOException {
        throw new UnsupportedOperationException("Not supported");
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
    }

    @Override
    public void dispose() {
        if (output != null) {
            try { output.close(); } catch (IOException ignored) {}
            this.output = null;
        }
    }

    @Override
    public void close() {
        dispose();
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata metadata, ImageTypeSpecifier type, ImageWriteParam param) {
        return metadata;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata metadata, ImageWriteParam param) {
        return metadata;
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier type, ImageWriteParam param) {
        return null;
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int index) throws IOException {
        return null;
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return type != null && (type.getColorModel().getNumComponents() == 3 ||
                type.getColorModel().getNumComponents() == 4);
    }

    @Override
    public void reset() {
        super.reset();
        this.output = null;
    }
}
