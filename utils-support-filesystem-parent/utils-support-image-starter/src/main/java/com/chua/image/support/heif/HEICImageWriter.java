package com.chua.image.support.heif;

import io.github.lukas81298.imageio.heif.HeifImageWriter;

import javax.imageio.IIOException;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * HEIC/HEIF 图片写入器实现。
 *
 * <p>封装 {@link HeifImageWriter}，对外暴露标准 {@link ImageWriter} SPI 接口，
 * 支持将 {@link BufferedImage} 写入 HEIC/HEIF 格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HEICImageWriter extends ImageWriter {

    private final HEICImageWriterSpi spi;
    private ImageOutputStream output;
    private BufferedImage sourceImage;

    /**
     * 构造函数。
     *
     * @param spi 关联的 SPI
     */
    public HEICImageWriter(HEICImageWriterSpi spi) {
        super(spi);
        this.spi = spi;
    }

    @Override
    public void setOutput(Object output) {
        this.output = (ImageOutputStream) output;
        this.sourceImage = null;
    }

    @Override
    public void prepareWriteEmpty(IIOMetadata streamMetadata,
                                   IIOMetadata imageMetadata,
                                   ImageWriteParam params) throws IOException {
        throw new UnsupportedOperationException("HEIC writer does not support empty image write");
    }

    @Override
    public void write(IIOMetadata metadata, javax.imageio.IIOImage image, ImageWriteParam param) throws IOException {
        if (output == null) {
            throw new IOException("Output not set");
        }
        BufferedImage img = image.getImage();
        if (img == null) {
            throw new IOException("No image data");
        }
        float quality = param != null && param.isCompressionSet()
                ? param.getCompressionQuality() : 0.9f;

        try {
            HeifImageWriter delegate = new HeifImageWriter();
            delegate.setOutput(output);
            delegate.write(img, quality);
            delegate.dispose();
        } catch (IIOException e) {
            throw new IOException("HEIC encode failed", e.getIOException());
        }
    }

    @Override
    public void flush() {
        sourceImage = null;
    }

    @Override
    public void dispose() throws IOException {
        output = null;
        sourceImage = null;
    }

    @Override
    public void close() throws IOException {
        output = null;
        sourceImage = null;
    }
}
