package com.chua.common.support.media.codec;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.ffmpeg.support.codec.EncodesFrame;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
* 基于纯 Java 镜像io 的 MJPEG/JPEG 编码器。
*
* <p>低延迟、软件编码。硬件加速不可用时优雅降级。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"jpeg", "mjpeg"}, order = 30)
public class JpegVideoEncoder implements VideoEncoder, EncodesFrame {

    /**
    * JPEG 编码格式名称
    */
    private static final String FORMAT_JPEG = "JPEG";

    /**
    * 默认图片质量 0.8
    */
    private static final double DEFAULT_QUALITY = 0.8;

    /**
    * JPEG 编码格式名称
    */
    private static final String CODEC_NAME_JPEG = "jpeg";

    /**
    * JPEG 图片质量
    */
    private double quality = DEFAULT_QUALITY;

    /**
    * 编码器是否已启动
    */
    private boolean started = true;

    /**
    * 空构造。
    */
    public JpegVideoEncoder() {
    }

    /**
    * 使用宽高和帧率构造并初始化。
    *
    * @param width 视频宽度
    * @param height 视频高度
    * @param fps 帧率
    */
    public JpegVideoEncoder(int width, int height, int fps) {
        init(width, height, fps);
    }

    /**
    * 使用包装类型宽高和帧率构造，空 时跳过初始化。
    *
    * @param width 视频宽度
    * @param height 视频高度
    * @param fps 帧率
    */
    public JpegVideoEncoder(Integer width, Integer height, Integer fps) {
        if (width != null && height != null && fps != null) {
            init(width, height, fps);
        }
    }

    /**
    * 使用可变参数构造，前三个参数分别为宽高和帧率。
    *
    * @param args 可变参数数组
    */
    public JpegVideoEncoder(Object... args) {
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
        // 重置编码质量为默认值
        this.quality = DEFAULT_QUALITY;
        this.started = true;
        log.info("[JpegVideoEncoder] 已启动: {}x{} {}fps (MJPEG 编码)", width, height, fps);
    }

    @Override
    /** 获取codec名称 */
    public String getCodecName() {
        return CODEC_NAME_JPEG;
    }

    @Override
    /** 获取codecid */
    public int getCodecId() {
        return 0;
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

    @Override
    /** 编码 */
    public synchronized byte[] encode(BufferedImage image) {
        if (!started || image == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(image, FORMAT_JPEG, bos);
            return bos.toByteArray();
        } catch (Throwable e) {
            log.warn("[JpegVideoEncoder] encode(BufferedImage) 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    /** 编码 */
    public synchronized byte[] encode(Frame frame) {
        if (!started || frame == null) {
            return new byte[0];
        }
        try {
            Java2DFrameConverter converter = new Java2DFrameConverter();
            BufferedImage image = converter.convert(frame);
            return encode(image);
        } catch (Throwable e) {
            log.warn("[JpegVideoEncoder] encode(Frame) 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    /** 关闭 */
    public synchronized void close() {
        started = false;
    }
}
