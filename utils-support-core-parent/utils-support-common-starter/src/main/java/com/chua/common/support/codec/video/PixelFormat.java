package com.chua.common.support.codec.video;

/**
 * 视频像素格式 —— 编解码器之间交换原始帧时使用的中性表示。
 *
 * <p>本枚举刻意不引入任何第三方（JavaCV / FFmpeg）类型，
 * 使 SPI 可被纯 Java、JNI、FFM 三类实现共同使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum PixelFormat {

    /**
     * 平面 YUV 4:2:0，8 位，3 平面（Y / U / V）。
     */
    YUV420P(3),

    /**
     * 半平面 YUV 4:2:0，8 位，2 平面（Y / UV 交织）。
     * <p>NVENC / NVDEC 的原生输入输出格式。</p>
     */
    NV12(2),

    /**
     * 打包 BGR，8 位每通道，单平面。
     */
    BGR24(1),

    /**
     * 打包 RGB，8 位每通道，单平面。
     */
    RGB24(1),

    /**
     * 打包 BGRA，8 位每通道，单平面。
     */
    BGRA(1),

    /**
     * 8 位灰度，单平面。
     */
    GRAY(1);

    /**
     * 平面数量。
     */
    private final int planes;

    /**
     * 构造像素格式。
     *
     * @param planes 平面数量
     */
    PixelFormat(int planes) {
        this.planes = planes;
    }

    /**
     * 获取平面数量。
     *
     * @return 该格式的平面数
     */
    public int planes() {
        return planes;
    }
}
