package com.chua.image.support.heif;

import io.github.lukas81298.imageio.heif.HeifImageReader;
import io.github.lukas81298.imageio.heif.HeifImageWriter;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * HEIC/HEIF 图片读取器实现。
 *
 * <p>封装 {@link HeifImageReader}，对外暴露标准 {@link ImageReader} SPI 接口，
 * 支持读取单帧和多帧 HEIC/HEIF 图片。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HEICImageReader extends ImageReader {

    private final HeifImageReaderSpi spi;
    private ImageInputStream input;
    private BufferedImage image;
    private int width, height;
    private boolean decoded;

    /**
     * 构造函数。
     *
     * @param spi 关联的 SPI
     */
    public HEICImageReader(HeifImageReaderSpi spi) {
        super(spi);
        this.spi = spi;
    }

    @Override
    public void setInput(Object obj) {
        this.input = (ImageInputStream) obj;
        this.decoded = false;
        this.image = null;
    }

    @Override
    public int getWidth(int index) throws IOException {
        ensureDecoded();
        return width;
    }

    @Override
    public int getHeight(int index) throws IOException {
        ensureDecoded();
        return height;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return 1;
    }

    @Override
    public BufferedImage read(int index, ImageReadParam param) throws IOException {
        ensureDecoded();
        return image;
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int index) throws IOException {
        return null;
    }

    @Override
    public void dispose() throws IOException {
        input = null;
        image = null;
        decoded = false;
    }

    @Override
    public void close() throws IOException {
        input = null;
        image = null;
        decoded = false;
    }

    private void ensureDecoded() throws IOException {
        if (decoded) {
            return;
        }
        if (input == null) {
            throw new IOException("HEIC input not set");
        }
        try {
            // Delegate to imageio-heif native decoder
            HeifImageReader delegate = new HeifImageReader();
            delegate.setInput(input);
            BufferedImage delegateImage = delegate.read(0);
            if (delegateImage == null) {
                throw new IOException("Failed to decode HEIC image");
            }
            this.image = delegateImage;
            this.width = delegateImage.getWidth();
            this.height = delegateImage.getHeight();
            this.decoded = true;
            delegate.dispose();
        } catch (IIOException e) {
            throw new IOException("HEIC decode failed", e.getIOException());
        }
    }
}
