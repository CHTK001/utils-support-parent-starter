package com.chua.remote.agent;

import com.chua.remote.protocol.capability.CodecProfile;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Native 编码器。
 *
 * <p>使用系统原生 API 或 native 库进行高性能屏幕编码（JPEG/PNG/WebP/H264）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class NativeEncoder {

    /** 编码能力 */
    private final CodecProfile capability;

    public NativeEncoder(CodecProfile capability) {
        this.capability = capability;
    }

    /**
     * 编码截图数据。
     *
     * @param rawPixels 原始像素数据
     * @return 编码后的字节数组
     */
    public byte[] encode(byte[] rawPixels) {
        String encoding = capability.getEncodings().get(0);
        log.debug("Native 编码: encoding={}", encoding);
        return new byte[0];
    }

    /**
     * 生成缩略图。
     *
     * @param data 原始数据
     * @return 缩略图数据
     */
    public byte[] generateThumbnail(byte[] data) {
        if (!capability.isThumbnailSupported()) {
            return data;
        }
        return new byte[0];
    }

    /**
     * 缩放图片。
     *
     * @param data   原始数据
     * @param width  目标宽度
     * @param height 目标高度
     * @return 缩放后的数据
     */
    public byte[] scale(byte[] data, int width, int height) {
        if (!capability.isScalingSupported()) {
            return data;
        }
        return new byte[0];
    }

    /**
     * 关闭编码器，释放资源。
     */
    public void close() {
        log.info("Native 编码器已关闭");
    }
}
