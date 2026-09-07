package com.chua.remote.agent;

/**
 * 自研编码器 SPI（原始帧编码契约——不经 BufferedImage）。
 *
 * <p>JPEG/H264 编码器均实现本接口并经 SPI 注册（META-INF/services），
 * {@link NativeEncoder} 按协商编码名加载对应扩展——编码层全部走 SPI 接口架构，
 * 不直连具体编码类。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FrameEncoderSpi {

    /**
     * 编码名（jpeg/h264——与协商编码集一致）。
     *
     * @return 编码名
     */
    String codecName();

    /**
     * 编码原始 RGB 像素帧。
     *
     * @param frame   原始 RGB 像素帧
     * @param quality 画质（0-100，0 表示编码器默认）
     * @return 编码后字节
     */
    byte[] encode(NativeFrame frame, int quality);

    /**
     * 关闭编码器（H264 的原生句柄释放等）。
     */
    default void close() {
    }
}
