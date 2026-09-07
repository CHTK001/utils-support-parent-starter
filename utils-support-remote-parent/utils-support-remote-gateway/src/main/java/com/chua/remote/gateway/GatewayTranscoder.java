package com.chua.remote.gateway;

import com.chua.nativevideocodec.support.NativeVideoCodec;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 网关转码器：控制端与被控端协商无交集时，负责 H264 ↔ JPEG 双向转码。
 *
 * <p>JPEG → H264：ImageIO 解码 → BGR24 显式拷贝 → 原生 h264 编码
 * （{@link NativeVideoCodec}——h264EncoderCreate/h264Encode）。</p>
 * <p>H264 → JPEG：原生 h264 解码（h264DecoderCreate/h264Decode）→ RGB 显式转换 →
 * Raster 包装 ImageWriter（不经 BufferedImage 中间态）。</p>
 * <p>被控端编码为 freerdp/RDP 自有码流（RemoteFX 等）时 web 无法直接播放：
 * 当前架构下 freerdp 为套壳模式（本地三方进程），其窗口经 agent 屏幕采集重编码为
 * h264/jpeg 后转发，RDP 码流不直达网关；未来直传 RDP 流时需 freerdp 解码器转码（另项）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class GatewayTranscoder {

    /** JPEG 编码格式名 */
    private static final String JPEG = "jpeg";

    /** H264 编码格式名 */
    private static final String H264 = "h264";

    private GatewayTranscoder() {
    }

    /**
     * 转码一帧数据。
     *
     * @param sourceData     源帧字节
     * @param sourceEncoding 源编码（jpeg/h264）
     * @param targetEncoding 目标编码（jpeg/h264）
     * @param width          帧宽
     * @param height         帧高
     * @return 转码后字节（不支持的组合返回空数组）
     */
    public static byte[] transcode(byte[] sourceData, String sourceEncoding,
                                   String targetEncoding, int width, int height) {
        if (sourceData == null || sourceData.length == 0) {
            return new byte[0];
        }
        String source = normalize(sourceEncoding);
        String target = normalize(targetEncoding);
        try {
            if (JPEG.equals(source) && H264.equals(target)) {
                return jpegToH264(sourceData, width, height);
            }
            if (H264.equals(source) && JPEG.equals(target)) {
                return h264ToJpeg(sourceData, width, height);
            }
            log.warn("不支持的转码组合: {} -> {}", sourceEncoding, targetEncoding);
            return new byte[0];
        } catch (Throwable e) {
            log.error("转码失败: {} -> {}", sourceEncoding, targetEncoding, e);
            return new byte[0];
        }
    }

    /**
     * JPEG → H264：ImageIO 解码 + 原生 h264 编码。
     */
    private static byte[] jpegToH264(byte[] jpegData, int width, int height) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegData));
        if (image == null) {
            return new byte[0];
        }
        // 统一为 BGR24 字节（原生编码器输入格式）
        byte[] bgr = ensureBgrBytes(image);
        long encoder = NativeVideoCodec.h264EncoderCreate(width, height, 30, 23, 1, 1);
        if (encoder == 0) {
            log.warn("[GatewayTranscoder] h264 编码器创建失败");
            return new byte[0];
        }
        try {
            return NativeVideoCodec.h264Encode(encoder, bgr, width, height);
        } finally {
            NativeVideoCodec.h264EncoderFree(encoder);
        }
    }

    /**
     * H264 → JPEG：原生 h264 解码 + Raster 包装 JPEG 编码（不经 BufferedImage 中间态）。
     */
    private static byte[] h264ToJpeg(byte[] h264Data, int width, int height) {
        long decoder = NativeVideoCodec.h264DecoderCreate(width, height);
        if (decoder == 0) {
            log.warn("[GatewayTranscoder] h264 解码器创建失败");
            return new byte[0];
        }
        try {
            byte[] bgr = NativeVideoCodec.h264Decode(decoder, h264Data, h264Data.length);
            if (bgr == null || bgr.length == 0) {
                return new byte[0];
            }
            // BGR → RGB 显式转换（编码器输入为 RGB）
            byte[] rgb = new byte[bgr.length];
            for (int i = 0; i + 2 < bgr.length; i += 3) {
                rgb[i] = bgr[i + 2];
                rgb[i + 1] = bgr[i + 1];
                rgb[i + 2] = bgr[i];
            }
            return encodeJpegRaster(rgb, width, height, 80);
        } finally {
            NativeVideoCodec.h264DecoderFree(decoder);
        }
    }

    /**
     * 原始 RGB 字节 → JPEG（Raster 包装 ImageWriter——不经 BufferedImage）。
     */
    private static byte[] encodeJpegRaster(byte[] rgb, int width, int height, int quality) throws IOException {
        PixelInterleavedSampleModel sampleModel = new PixelInterleavedSampleModel(
                DataBuffer.TYPE_BYTE, width, height, 3, width * 3, new int[]{0, 1, 2});
        DataBuffer dataBuffer = new DataBufferByte(rgb, rgb.length);
        WritableRaster raster = Raster.createWritableRaster(sampleModel, dataBuffer, null);
        RenderedImage image = new GatewayRasterImage(raster, width, height);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(JPEG);
        if (!writers.hasNext()) {
            return new byte[0];
        }
        ImageWriter writer = writers.next();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(rgb.length / 3);
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
        } finally {
            writer.dispose();
        }
    }

    /**
     * BufferedImage → BGR24 字节（原生编码器输入格式）。
     */
    private static byte[] ensureBgrBytes(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            byte[] pixels = new byte[image.getWidth() * image.getHeight() * 3];
            image.getRaster().getDataElements(0, 0, image.getWidth(), image.getHeight(), pixels);
            return pixels;
        }
        BufferedImage bgr = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        java.awt.Graphics2D g = bgr.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        byte[] pixels = new byte[bgr.getWidth() * bgr.getHeight() * 3];
        bgr.getRaster().getDataElements(0, 0, bgr.getWidth(), bgr.getHeight(), pixels);
        return pixels;
    }

    /**
     * 编码名归一化（忽略大小写/前后空格）。
     */
    private static String normalize(String encoding) {
        return encoding == null ? null : encoding.trim().toLowerCase();
    }
}
