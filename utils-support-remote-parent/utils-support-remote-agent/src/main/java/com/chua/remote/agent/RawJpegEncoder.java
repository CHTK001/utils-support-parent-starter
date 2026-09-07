package com.chua.remote.agent;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 原始 RGB 像素 → JPEG 编码器（不经 BufferedImage）。
 *
 * <p>构造包装原始 RGB 字节的 raster（{@link PixelInterleavedSampleModel} +
 * {@link DataBufferByte}），经 {@link RawRasterImage}（RenderedImage 包装）直接交给
 * ImageIO 的 JPEG writer——编码链路全程不经过 {@link java.awt.image.BufferedImage}。
 * raster 直接包装原始数组为 ImageIO 标准实践（writer 读取即消费，非零拷贝共享缓冲机制）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RawJpegEncoder {

    private RawJpegEncoder() {
    }

    /**
     * 编码原始 RGB 像素帧为 JPEG。
     *
     * @param frame   原始 RGB 像素帧
     * @param quality 压缩质量（0-100，0 表示编码器默认）
     * @return JPEG 字节
     */
    public static byte[] encodeJpeg(NativeFrame frame, int quality) {
        int width = frame.width();
        int height = frame.height();
        byte[] pixels = frame.pixels();
        // 包装原始 RGB 字节的 raster（3 波段交错——R/G/B）
        SampleModel sampleModel = new PixelInterleavedSampleModel(
                DataBuffer.TYPE_BYTE, width, height, 3, width * 3, new int[]{0, 1, 2});
        DataBuffer dataBuffer = new DataBufferByte(pixels, pixels.length);
        WritableRaster raster = Raster.createWritableRaster(sampleModel, dataBuffer, null);
        RenderedImage image = new RawRasterImage(raster, width, height);
        // ImageIO JPEG writer 直接消费 RenderedImage
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("[RawJpegEncoder] 无可用 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(pixels.length / 3);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(Math.max(0f, Math.min(1f, quality / 100f)));
                }
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("[RawJpegEncoder] JPEG 编码失败", e);
        } finally {
            writer.dispose();
        }
    }
}
