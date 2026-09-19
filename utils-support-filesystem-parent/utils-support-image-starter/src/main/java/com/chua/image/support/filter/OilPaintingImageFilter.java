package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;


/**
 * 油画风格滤镜
 * <p>
 * 模拟古典油画的厚涂质感：
 * 1. 颜色量化：将颜色映射到有限的油画调色板，减少"数码感"
 * 2. 笔触方向采样：沿局部梯度方向混合邻域颜色，产生笔触纹理
 * 3. 对比度增强：油画通常对比度比照片更强
 * 4. 暖色调偏移：模拟油画颜料的暖色倾向
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认油画风格
 * BufferedImage oil = new OilPaintingImageFilter().converter(src);
 *
 * // 更粗笔触 + 更多色阶
 * OilPaintingImageFilter filter = new OilPaintingImageFilter()
 *         .setBrushSize(6)
 *         .setColorLevels(16);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>brushSize</b>（默认 4，范围 2-16）：笔触采样半径（像素），越大笔触越粗</li>
 *   <li><b>colorLevels</b>（默认 8，范围 2-32）：每通道颜色量化级数，越小颜色越少越"油画"</li>
 *   <li><b>warmTone</b>（默认 0.2，范围 0.0-1.0）：暖色调偏移强度</li>
 *   <li><b>contrast</b>（默认 1.2，范围 0.5-3.0）：对比度增强系数</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>colorLevels 越小，颜色越集中，油画感越强，但细节损失越多</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("oilpainting")
@SpiDescribe("油画风格滤镜")
@Accessors(chain = true)
public class OilPaintingImageFilter extends AbstractImageFilter {

    /**
     * 笔触采样半径（像素），默认 4
     */
    private int brushSize = 4;

    /**
     * 每通道颜色量化级数，默认 8
     */
    private int colorLevels = 8;

    /**
     * 暖色调偏移强度 (0.0-1.0)，默认 0.2
     */
    private double warmTone = 0.2;

    /**
     * 对比度增强系数 (0.5-3.0)，默认 1.2
     */
    private double contrast = 1.2;

    /**
     * 执行油画风格滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 油画风格图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        int quantStep = 255 / Math.max(1, colorLevels - 1);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int orig = argb[i];
                int r = (orig >> 16) & 0xff;
                int g = (orig >> 8) & 0xff;
                int b = orig & 0xff;

                // 第一步：沿 3x3 邻域梯度方向采样笔触
                int sumR = 0, sumG = 0, sumB = 0, count = 0;
                int bx = Math.min(brushSize, 3);
                for (int dy = -bx; dy <= bx; dy += 2) {
                    for (int dx = -bx; dx <= bx; dx += 2) {
                        int sx = x + dx;
                        int sy = y + dy;
                        if (sx < 0 || sx >= w || sy < 0 || sy >= h) {
                            continue;
                        }
                        int p = argb[sy * w + sx];
                        sumR += (p >> 16) & 0xff;
                        sumG += (p >> 8) & 0xff;
                        sumB += p & 0xff;
                        count++;
                    }
                }
                if (count > 0) {
                    r = sumR / count;
                    g = sumG / count;
                    b = sumB / count;
                }

                // 第二步：颜色量化
                r = quantize(r, quantStep);
                g = quantize(g, quantStep);
                b = quantize(b, quantStep);

                // 第三步：暖色调偏移（红+蓝通道微调）
                r = clamp(r + (int) (warmTone * 20));
                b = clamp(b - (int) (warmTone * 15));

                // 第四步：对比度增强
                r = clamp((int) (((r - 128) * contrast + 128)));
                g = clamp((int) (((g - 128) * contrast + 128)));
                b = clamp((int) (((b - 128) * contrast + 128)));

                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
     * 颜色量化
     *
     * @param value 原始通道值
     * @param step  量化步长
     * @return 量化后的值
     */
    private int quantize(int value, int step) {
        return (int) (Math.round(value / (double) step) * step);
    }

    /**
     * 通道值钳制 0-255
     *
     * @param v 原始值
     * @return 钳制后的值
     */
    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
