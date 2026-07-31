package com.chua.remote.support.gateway.transport.codec;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * H264 → JPEG 转码器
 * 使用 JavaCV(FFmpeg) 解码 H264 帧，ImageIO 编码 JPEG
 * 用于 Gateway 侧，当客户端不支持 H264 时自动转码

 * @author CH
 */@Slf4j
public class H264ToJpegTranscoder {

    /** H264 编码标识 */
    private static final String SOURCE_CODEC = "H264";
    /** JPEG 编码标识 */
    private static final String TARGET_CODEC = "JPEG";

    /**
     * 获取源编码格式
     *
     * @return H264
     */
    public String sourceCodec() { return SOURCE_CODEC; }

    /**
     * 获取目标编码格式
     *
     * @return JPEG
     */
    public String targetCodec() { return TARGET_CODEC; }

    /**
     * 将单帧 H264 数据转码为 JPEG
     * @param h264Data   H264 裸数据（单帧 Annex B）
     * @param width      原始宽度(用于 fallback)
     * @param height     原始高度(用于 fallback)
     * @return JPEG 字节数组，失败返回 null
     */
    public byte[] transcode(byte[] h264Data, int width, int height) {
        // 尝试 JavaCV 解码
        byte[] jpeg = decodeViaJavacv(h264Data);
        if (jpeg != null) { return jpeg; }

        // fallback: 返回空
        log.warn("[H264ToJpeg] 转码失败，返回空");
        return null;
    }

    /**
     * 使用 JavaCV FFmpegFrameGrabber 解码 H264 为 BufferedImage 再编码为 JPEG
     *
     * <p>通过 {@link ByteArrayInputStream} 将 H264 裸数据送入 FFmpeg 解码器，
     * 取回 RGB 帧后经 {@link Java2DFrameConverter} 转为 {@link BufferedImage}，
     * 最终由 {@link ImageIO} 写入 JPEG 字节数组。
     *
     * @param h264Data H264 裸数据
     * @return JPEG 字节数组，解码失败返回 null
     */
    private byte[] decodeViaJavacv(byte[] h264Data) {
        FFmpegFrameGrabber grabber = null;
        try {
            grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(h264Data));
            grabber.setFormat("h264");
            grabber.start();

            Frame frame = grabber.grabImage();
            if (frame == null) {
                log.warn("[H264ToJpeg] grabImage 返回 null");
                return null;
            }

            // JavaCV Frame → BufferedImage → JPEG
            try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                BufferedImage bi = converter.getBufferedImage(frame);
                if (bi == null) {
                    log.warn("[H264ToJpeg] 转换 BufferedImage 失败");
                    return null;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(bi, "JPEG", baos);
                return baos.toByteArray();
            }
        }
 catch (Exception e) {
            log.warn("[H264ToJpeg] 解码失败: {}", e.getMessage());
            return null;
        }
 finally {
            if (grabber != null) {
                try { grabber.stop(); }
 catch (Exception ignored) { log.trace("停止 grabber 失败", ignored); }
            }
        }
    }
}
