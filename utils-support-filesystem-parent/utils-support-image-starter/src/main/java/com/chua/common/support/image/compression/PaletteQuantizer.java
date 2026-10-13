package com.chua.common.support.image.compression;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;

/**
 * 调色板量化器 SPI —— 图片压缩的<b>native 钩子</b>。
 *
 * <p>PNG 压缩率的主要来源不是 deflate，而是把 24-bit RGB / 32-bit RGBA 降成
 * 8-bit 索引色调色板：像素数据量直接降到 1/3~1/4，且索引值高度重复，
 * deflate 的游程与匹配都会显著变短。这一步<b>必然有损</b>（24-bit 降到 8-bit 无法可逆），
 * 但通过在感知均匀色彩空间里合并「肉眼分不出」的颜色，视觉上几乎无损。
 *
 * <h3>为什么抽出 SPI</h3>
 * <p>纯 Java 的中位切分（median cut）在 1000 万像素级别上耗时可达数十秒。
 * 生产级方案是 {@code libimagequant}（即 pngquant 的内核），
 * 它用感知色彩空间 + K-Means 迭代细化 + 有序抖动，同样质量下比中位切分快两个数量级。
 * 抽出本接口后，纯 Java 实现作为默认兜底，native 实现注册后自动优先。
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>返回的 {@link QuantizedImage} 索引值必须落在调色板长度范围内</li>
 *   <li>当原图已是无损可表达的调色板图（色数 &lt;= maxColors）时，应原样返回，
 *       不引入任何额外误差</li>
 *   <li>实现必须无状态且线程安全</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface PaletteQuantizer {

    /**
     * 把真彩色图像量化为调色板图像。
     *
     * @param source    源图像
     * @param maxColors 调色板色数上限，取值 2~256
     * @param dithering  是否开启 Floyd-Steinberg 抖动
     * @return 量化结果，不会返回 {@code null}
     * @throws Exception 当源图像为空或量化失败时
     */
    QuantizedImage quantize(BufferedImage source, int maxColors, boolean dithering) throws Exception;

    /**
     * 获取量化器名称。
     *
     * @return 量化器名称，如 {@code median-cut}、{@code libimagequant}
     */
    String name();

    /**
     * 判断量化器是否可用。
     *
     * @return 可用返回 {@code true}
     */
    boolean available();

    /**
     * 量化结果。
     *
     * <p>以「索引数组 + 调色板」的形式表达，而非 {@code BufferedImage}，
     * 这样量化器实现不必关心目标图像类型（PNG-8 / GIF / 索引色 BMP 的落地方式由调用方决定）。
     *
     * @param width    图像宽度（像素）
     * @param height   图像高度（像素）
     * @param indices  索引数据，长度必须为 {@code width * height}
     * @param palette  调色板
     * @param colors   调色板实际颜色数
     * @author CH
     * @since 4.0.0.43
     */
    record QuantizedImage(int width, int height, byte[] indices, IndexColorModel palette, int colors) {

        /**
         * 构造量化结果
         *
         * @throws IllegalArgumentException 当宽高非正、索引长度不匹配或调色板为空时
         */
        public QuantizedImage {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("量化结果宽高必须为正数，实际=" + width + "x" + height);
            }
            if (palette == null) {
                throw new IllegalArgumentException("调色板不能为空");
            }
            if (indices == null || indices.length != width * height) {
                throw new IllegalArgumentException("索引数据长度必须等于 width * height");
            }
            if (colors < 1 || colors > palette.getMapSize()) {
                colors = palette.getMapSize();
            }
        }

        /**
         * 把量化结果转换为 {@code TYPE_BYTE_INDEXED} 图像。
         *
         * @return 可直接交给 ImageIO 写出的索引色图像
         */
        public BufferedImage toBufferedImage() {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, palette);
            image.getRaster().setDataElements(0, 0, width, height, indices);
            return image;
        }

        /**
         * 还原为 ARGB 真彩色图像，用于质量评估或转换到其他格式。
         *
         * @return 32-bit ARGB 图像
         */
        public BufferedImage toArgbImage() {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] argb = new int[width * height];
            for (int i = 0; i < indices.length; i++) {
                argb[i] = palette.getRGB(indices[i] & 0xFF);
            }
            image.setRGB(0, 0, width, height, argb, 0, width);
            return image;
        }
    }
}