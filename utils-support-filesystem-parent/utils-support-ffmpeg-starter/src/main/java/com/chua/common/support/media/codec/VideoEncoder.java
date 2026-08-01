package com.chua.common.support.media.codec;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

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
     * 编码单帧 BufferedImage。
     *
     * @param image BufferedImage 格式的输入帧
     * @return 编码后的字节数组
     */
    byte[] encode(BufferedImage image);

    /**
     * 编码单帧 BGR ByteBuffer（零拷贝路径）。
     *
     * @param bgrData BGR 格式的 DirectByteBuffer
     * @param width 帧宽度
     * @param height 帧高度
     * @return 编码后的字节数组
     */
    default byte[] encode(ByteBuffer bgrData, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = new byte[bgrData.remaining()];
        bgrData.get(pixels);
        image.getRaster().setDataElements(0, 0, width, height, pixels);
        return encode(image);
    }

    /**
     * 释放编码器资源。
     */
    void close();
}
