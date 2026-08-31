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
    public void prepareWriteEmpty(IIOMetadata streamMetadata, ImageTypeSpecifier type,
                                   int minWidth, int minHeight, IIOMetadata imgMeta,
                                   List<? extends BufferedImage> thumbnails, ImageWriteParam param) throws IOException {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void write(IIOMetadata metadata, javax.imageio.IIOImage image, ImageWriteParam param) throws IOException {
        if (output == null) throw new IOException("Output not set");
        java.awt.image.RenderedImage ri = image.getRenderedImage();
        if (!(ri instanceof BufferedImage)) throw new IOException("Only BufferedImage supported");
        BufferedImage img = (BufferedImage) ri;
        try {
            HeifNativeEncoder.encode(img, output);
        } catch (Exception e) {
            throw new IIOException("HEIC encode failed", e);
        }
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

    public Iterator<ImageTypeSpecifier> getImageTypes(int index) throws IOException {
        return null;
    }

    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return type != null && (type.getColorModel().getNumComponents() == 3 ||
                type.getColorModel().getNumComponents() == 4);
    }
}
