package com.chua.remote.agent;

import com.chua.common.support.spi.annotations.Spi;

/**
 * JPEG 编码器 SPI 实现（原始 RGB 帧 → JPEG，不经 BufferedImage）。
 *
 * <p>经 {@link RawJpegEncoder}（Raster 包装 + ImageIO ImageWriter）编码，
 * 编码层经 SPI 架构加载（@Spi("jpeg")），不直连。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("jpeg")
public class JpegFrameEncoder implements FrameEncoderSpi {

    @Override
    public String codecName() {
        return "jpeg";
    }

    @Override
    public byte[] encode(NativeFrame frame, int quality) {
        // 目标最大 1920x1080（对齐协商解码尺寸）——大屏采集帧整数降采样，ImageIO 编码提速 scale² 倍
        return RawJpegEncoder.encodeJpeg(frame, quality, 1920, 1080);
    }
}
