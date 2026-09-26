package com.chua.common.support.codec.image;

import com.chua.common.support.codec.Decoder;

import java.awt.Dimension;
import java.awt.image.BufferedImage;

/**
 * 图像解码器 SPI —— 把某种图像格式的字节流解码为 {@link BufferedImage}。
 *
 * <p>实现示例：{@code TurboJpegImageDecoder}（libjpeg-turbo 原生实现，含缩放解码）、
 * 基于 ImageIO 的纯 Java 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageDecoder extends Decoder<byte[], BufferedImage> {

    /**
     * 仅解析头部信息取得图像尺寸，不解码像素数据。
     *
     * @param data 图像文件字节
     * @return 图像宽高，单位像素
     */
    Dimension probeDimension(byte[] data);
}
