package com.chua.image.support.heif;

import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * HEIC/HEIF 图像读取器（纯Java实现）。
 *
 * <p>当系统有 libheif 可用时通过 JNI/Panama 调用；
 * 否则返回空，静默降级。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HEICImageReader extends ImageReader {

    private static final String[] FORMAT_NAMES = {"heic", "HEIC", "heif", "HEIF"};
    private static final String[] FILE_SUFFIXES = {"heic", "heif"};
    private static final String[] MIME_TYPES = {"image/heic", "image/heif"};

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
        this.input = null;
        this.decoded = false;
        this.image = null;
    }

    private void ensureDecoded() throws IOException {
        if (decoded) return;
        if (input == null) throw new IOException("Input not set");

        // 尝试通过 native 解码
        try {
            byte[] rgba = HeifNativeDecoder.decode(input);
            if (rgba != null && rgba.length >= 4) {
                // RGBA -> ARGB for BufferedImage
                int w = HeifNativeDecoder.getWidth();
                int h = HeifNativeDecoder.getHeight();
                this.width = w;
                this.height = h;
                this.image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int[] pixels = new int[w * h];
                for (int i = 0; i < w * h; i++) {
                    int r = rgba[i * 4] & 0xFF;
                    int g = rgba[i * 4 + 1] & 0xFF;
                    int b = rgba[i * 4 + 2] & 0xFF;
                    int a = rgba[i * 4 + 3] & 0xFF;
                    pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                this.image.setRGB(0, 0, w, h, pixels, 0, w);
                decoded = true;
                return;
            }
        } catch (Throwable e) {
            // native 不可用，静默降级
        }

        // 降级：标记为不支持
        throw new IOException("HEIC decoder not available (native libheif not found)");
    }
}
