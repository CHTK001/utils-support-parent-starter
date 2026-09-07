package com.chua.remote.agent;

/**
 * 原生屏幕采集帧（原始像素，不经 BufferedImage）。
 *
 * <p>替代 {@link java.awt.image.BufferedImage} 作为采集输出：原生采集直接产出
 * 原始 RGB 像素字节（显式拷贝），编码链路直接消费，避免 BufferedImage 转换开销。</p>
 *
 * @param width  宽度
 * @param height 高度
 * @param format 像素格式（RGB：每像素 3 字节 R/G/B）
 * @param pixels 像素字节（显式拷贝的独立缓冲）
 * @author CH
 * @since 4.0.0.42
 */
public record NativeFrame(int width, int height, String format, byte[] pixels) {

    /** RGB 像素格式标识 */
    public static final String FORMAT_RGB = "RGB";

    /**
     * 像素字节数。
     *
     * @return 像素缓冲长度
     */
    public int getSize() {
        return pixels.length;
    }
}
