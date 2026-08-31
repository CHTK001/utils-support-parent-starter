package com.chua.image.support.heif;

import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;

/**
 * HEIC/HEIF 图像读取器（纯Java实现）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HEICImageReader extends ImageReader {

    private ImageInputStream input;
    private boolean decoded = false;
    private BufferedImage image;
    private int width, height;

    public HEICImageReader(HEICImageReaderSpi spi) {
        super(spi);
    }

    @Override
    public void setInput(Object obj, boolean ignoreMetadata, boolean keepIsStreamPos) {
        this.input = (ImageInputStream) obj;
        this.decoded = false;
        this.image = null;
    }

    @Override
    public void setInput(Object obj) throws IOException {
        setInput(obj, false, false);
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
    public java.util.Iterator<ImageTypeSpecifier> getImageTypes(int index) throws IOException {
        ensureDecoded();
        return Arrays.asList(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)).iterator();
    }

    @Override
    public void reset() {
        super.reset();
        this.decoded = false;
        this.image = null;
    }

    @Override
    public void dispose() throws IOException {
        this.input = null;
        this.decoded = false;
        this.image = null;
    }

    @Override
    public void close() throws IOException {
        dispose();
    }

    private void ensureDecoded() throws IOException {
        if (decoded) return;
        if (input == null) throw new IOException("Input not set");
        try {
            byte[] rgba = HeifNativeDecoder.decode(input);
            if (rgba != null && rgba.length >= 4) {
                int w = HeifNativeDecoder.getWidth();
                int h = HeifNativeDecoder.getHeight();
                this.width = w;
                this.height = h;
                this.image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                int[] pixels = new int[w * h];
                for (int i = 0; i < w * h; i++) {
                    int r = rgba[i * 4] & 0xFF;
                    int g = rgba[i * 4 + 1] & 0xFF;
                    int b = rgba[i * 4 + 2] & 0xFF;
                    pixels[i] = (r << 16) | (g << 8) | b;
                }
                this.image.setRGB(0, 0, w, h, pixels, 0, w);
                decoded = true;
                return;
            }
        } catch (Throwable e) {
            // native unavailable
        }
        throw new IOException("HEIC decoder not available");
    }
}
