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
        return encodeJpeg(frame, quality, 0, 0);
    }

    /**
     * 编码原始 RGB 像素帧为 JPEG（支持目标尺寸降采样——大屏帧编码提速）。
     *
     * @param frame   原始 RGB 像素帧
     * @param quality 压缩质量（0-100，0 表示编码器默认）
     * @param maxW    目标最大宽度（{@code <= 0} 不缩放）
     * @param maxH    目标最大高度（{@code <= 0} 不缩放）
     * @return JPEG 字节
     */
    public static byte[] encodeJpeg(NativeFrame frame, int quality, int maxW, int maxH) {
        int width = frame.width();
        int height = frame.height();
        byte[] pixels = frame.pixels();
        int scale = 1;
        if (maxW > 0 && maxH > 0) {
            scale = Math.max(1, Math.max((width + maxW - 1) / maxW, (height + maxH - 1) / maxH));
        }
        int outW = width / scale;
        int outH = height / scale;
        byte[] out = pixels;
        if (scale > 1) {
            // 整数降采样（每 scale 个像素取 1）——构造缩略数组，ImageIO 编码像素量减少 scale² 倍
            out = new byte[outW * outH * 3];
            for (int y = 0; y < outH; y++) {
                int srcRow = y * scale * width * 3;
                int dstRow = y * outW * 3;
                for (int x = 0; x < outW; x++) {
                    int src = srcRow + x * scale * 3;
                    int dst = dstRow + x * 3;
                    out[dst] = pixels[src];
                    out[dst + 1] = pixels[src + 1];
                    out[dst + 2] = pixels[src + 2];
                }
            }
        }
        // 包装原始 RGB 字节的 raster（3 波段交错——R/G/B）
        SampleModel sampleModel = new PixelInterleavedSampleModel(
                DataBuffer.TYPE_BYTE, outW, outH, 3, outW * 3, new int[]{0, 1, 2});
        DataBuffer dataBuffer = new DataBufferByte(out, out.length);
        WritableRaster raster = Raster.createWritableRaster(sampleModel, dataBuffer, null);
        RenderedImage image = new RawRasterImage(raster, outW, outH);
        // ImageIO JPEG writer 直接消费 RenderedImage
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("[RawJpegEncoder] 无可用 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(out.length / 3);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(Math.max(0f, Math.min(1f, quality / 100f)));
                }
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("[RawJpegEncoder] JPEG 编码失败", e);
        } finally {
            writer.dispose();
        }
    }
}
