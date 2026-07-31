package com.chua.common.support.media.codec;

import java.awt.image.BufferedImage;

/**
 * 视频编码器接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VideoEncoder {

    /**
     * 获取编码器名称。
     *
     * @return 编码器名称
     */
    String getCodecName();

    /**
     * 获取编码器标识。
     *
     * @return FFmpeg 编解码器标识
     */
    int getCodecId();

    /**
     * 是否启用硬件加速。
     *
     * @return true 表示硬件加速已启用
     */
    boolean isHardwareAccelerated();

    /**
     * 请求下一个帧为关键帧。
     */
    void forceKeyFrame();

    /**
     * 编码单帧图像。
     *
     * @param image BufferedImage 格式的输入帧
     * @return 编码后的字节数组
     */
    byte[] encode(BufferedImage image);

    /**
     * 释放编码器资源。
     */
    void close();
}
