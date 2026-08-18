package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;

/**
 * JPEG 解码器 — 基于 Java ImageIO 实现。
 *
 * <p>使用 {@link javax.imageio.ImageIO#read(ByteArrayInputStream)} 解码 JPEG 数据，输出 ARGB 格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jpeg")
public class JpegVideoDecoder implements VideoDecoder {

    /** 宽度 */
    private int width;
    /** 高度 */
    private int height;

    /**
     * 默认构造器（SPI 使用）。
     */
    public JpegVideoDecoder() {
    }

    @Override
    public boolean init(int codecId, int width, int height) {
        this.width = width;
        this.height = height;
        return true;
    }

    @Override
    public ByteBuffer decode(byte[] packet) {
        if (packet == null || packet.length == 0) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(packet));
            if (image == null) {
                return null;
            }
            // 转为 ARGB 格式
            BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(image, 0, 0, null);
            byte[] pixels = new byte[argb.getWidth() * argb.getHeight() * 4];
            for (int y = 0; y < argb.getHeight(); y++) {
                for (int x = 0; x < argb.getWidth(); x++) {
                    int rgb = argb.getRGB(x, y);
                    int offset = (y * argb.getWidth() + x) * 4;
                    pixels[offset] = (byte) ((rgb >> 16) & 0xFF);
                    pixels[offset + 1] = (byte) ((rgb >> 8) & 0xFF);
                    pixels[offset + 2] = (byte) (rgb & 0xFF);
                    pixels[offset + 3] = (byte) ((rgb >> 24) & 0xFF);
                }
            }
            return ByteBuffer.wrap(pixels);
        } catch (Exception e) {
            log.warn("[JpegVideoDecoder] 解码失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public ByteBuffer[] flush() {
        return new ByteBuffer[0];
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void close() {
    }
}