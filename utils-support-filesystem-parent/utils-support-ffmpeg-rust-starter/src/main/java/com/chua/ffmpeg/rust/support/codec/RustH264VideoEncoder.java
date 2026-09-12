package com.chua.ffmpeg.rust.support.codec;

import com.chua.common.support.media.codec.VideoEncoder;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.ffmpeg.support.codec.EncodesFrame;
import com.chua.nativevideocodec.support.NativeVideoCodec;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;

/**
* 基于原生 Rust JNI 的 H.264 编码器。
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("rust-h264")
public class RustH264VideoEncoder implements VideoEncoder, EncodesFrame {

    /**
    * 编码器原生句柄
     */
    private long encoderHandle;

    /**
    * 视频宽度
     */
    private int width;

    /**
    * 视频高度
     */
    private int height;

    /**
    * 帧率
     */
    private int fps;

    /**
    * 编码器是否已启动
     */
    private boolean started;

    /**
    * H.264 编码格式名称
     */
    private static final String CODEC_NAME_H264 = "h264";

    /**
    * H.264 编解码器标识
     */
    private static final int AV_CODEC_ID_H264 = 27;

    /**
    * 默认画质值
     */
    private static final int DEFAULT_QUALITY = 23;

    /**
    * 默认 preset 值
     */
    private static final int DEFAULT_PRESET = 1;

    /**
    * 默认 配置文件 值
     */
    private static final int DEFAULT_PROFILE = 1;

    /**
    * 空构造。
     */
    public RustH264VideoEncoder() {
    }

    /**
    * 使用宽高和帧率构造并初始化。
    *
    * @param width 视频宽度
    * @param height 视频高度
    * @param fps 帧率
     */
    public RustH264VideoEncoder(int width, int height, int fps) {
        init(width, height, fps);
    }

    /**
    * 使用包装类型宽高和帧率构造，空 时跳过初始化。
    *
    * @param width 视频宽度
    * @param height 视频高度
    * @param fps 帧率
     */
    public RustH264VideoEncoder(Integer width, Integer height, Integer fps) {
        if (width != null && height != null && fps != null) {
            init(width, height, fps);
        }
    }

    /**
    * 使用可变参数构造，前三个参数分别为宽高和帧率。
    *
    * @param args 可变参数数组
     */
    public RustH264VideoEncoder(Object... args) {
        if (args != null && args.length >= 3
                && args[0] instanceof Number
                && args[1] instanceof Number
                && args[2] instanceof Number) {
            init(((Number) args[0]).intValue(),
                    ((Number) args[1]).intValue(),
                    ((Number) args[2]).intValue());
        }
    }

    /**
    * 初始化
    *
    * @param width width
    * @param height height
    * @param fps fps
     */
    public synchronized void init(int width, int height, int fps) {
        close();
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        this.encoderHandle = NativeVideoCodec.h264EncoderCreate(width, height, fps, DEFAULT_QUALITY, DEFAULT_PRESET, DEFAULT_PROFILE);
        this.started = encoderHandle != 0;
        log.info("[RustH264VideoEncoder] 已启动: {}x{} {}fps handle={}", width, height, fps, encoderHandle);
    }

    @Override
    /** 获取codec名称 */
    public String getCodecName() {
        return "h264";
    }

    @Override
    /** 获取codecid */
    public int getCodecId() {
        return 27;
    }

    @Override
    /** 是否hardware加速 */
    public boolean isHardwareAccelerated() {
        return false;
    }

    @Override
    /** force键帧 */
    public void forceKeyFrame() {
    }

    /**
    * ensure初始化
    *
    * @param w w
    * @param h h
    * @param f f
     */
    private void ensureInitialized(int w, int h, int f) {
        if (!started || encoderHandle == 0) {
            init(w, h, f);
        }
    }

    @Override
    /** 编码 */
    public synchronized byte[] encode(BufferedImage image) {
        if (image == null) {
            return new byte[0];
        }
        ensureInitialized(image.getWidth(), image.getHeight(), 30);
        if (!started || encoderHandle == 0) {
            return new byte[0];
        }
        try {
            byte[] bgr = ensureBgrBytes(image);
            return NativeVideoCodec.h264Encode(encoderHandle, bgr, width, height);
        } catch (Throwable e) {
            log.warn("[RustH264VideoEncoder] encode(BufferedImage) 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    /** 编码 */
    public synchronized byte[] encode(org.bytedeco.javacv.Frame frame) {
        if (frame == null) {
            return new byte[0];
        }
        ensureInitialized(Math.max(frame.imageWidth, 640), Math.max(frame.imageHeight, 480), 30);
        if (!started || encoderHandle == 0) {
            return new byte[0];
        }
        try {
            BufferedImage image = new org.bytedeco.javacv.Java2DFrameConverter().convert(frame);
            if (image == null) {
                return new byte[0];
            }
            return encode(image);
        } catch (Throwable e) {
            log.warn("[RustH264VideoEncoder] encode(Frame) 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    /** 关闭 */
    public synchronized void close() {
        if (encoderHandle != 0) {
            NativeVideoCodec.h264EncoderFree(encoderHandle);
            encoderHandle = 0;
        }
        started = false;
    }

    /**
    * 确保 缓冲镜像 转换为 BGR24 字节数组。
    *
    * @param image 源图像
    * @return BGR24 字节数组
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
}
