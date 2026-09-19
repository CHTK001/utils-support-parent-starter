package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 线稿（Line Art）滤镜
 * <p>
 * 提取图像边缘线条，生成干净的"描线稿"：
 * 1. 灰度化：NTSC 灰度
 * 2. 高斯平滑（可关）：降噪，减少碎线
 * 3. 边缘检测：Sobel 梯度幅值
 * 4. 反色 + 二值化/灰度：线条变黑、背景变白
 * 5. 阈值控制：只保留强边缘（避免碎线）
 * 6. 线条粗细（膨胀/多次叠加）：让线更连贯
 *
 * 输出是一张"白底黑线"的线稿，常用于手办/模型打印、动漫描线、技术制图。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认线稿
 * BufferedImage la = new LineArtImageFilter().converter(src);
 *
 * // 更细碎线（低阈值）+ 更粗线条
 * LineArtImageFilter filter = new LineArtImageFilter()
 *         .setThreshold(60)
 *         .setLineWidth(2)
 *         .setSmooth(true);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>threshold</b>（默认 100，范围 0-255）：边缘阈值，越低保留越多（含碎线），越高越干净</li>
 *   <li><b>lineWidth</b>（默认 1，范围 1-4）：线条粗细（膨胀迭代次数）</li>
 *   <li><b>smooth</b>（默认 true）：是否先做高斯平滑降噪（减少碎线）</li>
 *   <li><b>lineColor</b>（默认 0，范围 0-255）：线条颜色（0=黑，255=白）</li>
 *   <li><b>bgColor</b>（默认 255，范围 0-255）：背景颜色（默认白）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明）</li>
 *   <li>threshold 是关键参数：风景/照片图建议 80-120，低对比图需更低</li>
 *   <li>lineWidth &gt; 1 时用膨胀加粗，会让线变"胖"</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/18
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("lineart")
@SpiDescribe("线稿滤镜")
@Accessors(chain = true)
public class LineArtImageFilter extends AbstractImageFilter {

    /**
     * 边缘阈值，默认 100
     */
    private int threshold = 100;

    /**
     * 线条粗细，默认 1
     */
    private int lineWidth = 1;

    /**
     * 是否先平滑降噪，默认 true
     */
    private boolean smooth = true;

    /**
     * 线条颜色 (0-255)，默认 0（黑）
     */
    private int lineColor = 0;

    /**
     * 背景颜色 (0-255)，默认 255（白）
     */
    private int bgColor = 255;

    /**
     * 执行线稿滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 线稿图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        // 灰度
        int[] gray = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            gray[i] = ImageProcessorUtils.luminance(argb[i]);
        }

        // 可选平滑（3x3 盒模糊两趟）
        if (smooth) {
            gray = boxBlur(boxBlur(gray, w, h, 1), w, h, 1);
        }

        // Sobel 梯度幅值
        int[] edge = new int[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int gx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1]
                        + gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1];
                int gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1]
                        + gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1];
                edge[i] = (int) (Math.sqrt(gx * gx + gy * gy) / 2.0);
            }
        }

        // 二值化：梯度 > 阈值 → 线条
        int[] bin = new int[w * h];
        for (int i = 0; i < edge.length; i++) {
            bin[i] = edge[i] > threshold ? 1 : 0;
        }

        // 膨胀（加粗线条）
        if (lineWidth > 1) {
            for (int it = 0; it < lineWidth - 1; it++) {
                bin = dilate(bin, w, h);
            }
        }

        // 合成：线条=lineColor，背景=bgColor
        for (int i = 0; i < bin.length; i++) {
            int v = bin[i] == 1 ? lineColor : bgColor;
            outPixels[i] = (0xff << 24) | (v << 16) | (v << 8) | v;
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
     * 灰度图盒模糊
     *
     * @param src 灰度数组
     * @param w   宽
     * @param h   高
     * @param r   半径
     * @return 模糊后灰度数组
     */
    private int[] boxBlur(int[] src, int w, int h, int r) {
        int rr = Math.max(1, r);
        int[] tmp = new int[w * h];
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int from = Math.max(0, x - rr);
                int to = Math.min(w - 1, x + rr);
                int sum = 0, cnt = 0;
                for (int xx = from; xx <= to; xx++) {
                    sum += src[y * w + xx];
                    cnt++;
                }
                tmp[y * w + x] = sum / cnt;
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int from = Math.max(0, y - rr);
                int to = Math.min(h - 1, y + rr);
                int sum = 0, cnt = 0;
                for (int yy = from; yy <= to; yy++) {
                    sum += tmp[yy * w + x];
                    cnt++;
                }
                out[y * w + x] = sum / cnt;
            }
        }
        return out;
    }

    /**
     * 二值图膨胀（3x3 最大）
     *
     * @param src 二值数组（0/1）
     * @param w   宽
     * @param h   高
     * @return 膨胀后二值数组
     */
    private int[] dilate(int[] src, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int max = 0;
                for (int dy = -1; dy <= 1 && max == 0; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int sx = x + dx, sy = y + dy;
                        if (sx >= 0 && sx < w && sy >= 0 && sy < h) {
                            if (src[sy * w + sx] == 1) {
                                max = 1;
                                break;
                            }
                        }
                    }
                }
                out[y * w + x] = max;
            }
        }
        return out;
    }
}
