package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;


/**
 * 水彩画风格滤镜
 * <p>
 * 模拟水彩画的晕染效果：
 * 1. 颜色扩散：基于随机"颜料滴落"的扩散采样，产生水彩特有的晕染边界
 * 2. 纸张纹理：叠加细密的随机噪点，模拟水彩纸的纹理
 * 3. 低饱和度：水彩通常比照片颜色更柔和
 * 4. 留白：亮部区域保持偏白，模拟水彩的留白技法
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认水彩风格
 * BufferedImage wc = new WatercolorImageFilter().converter(src);
 *
 * // 更强晕染 + 更多纸张纹理
 * WatercolorImageFilter filter = new WatercolorImageFilter()
 *         .setDiffusionRadius(10)
 *         .setPaperTexture(0.4);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>diffusionRadius</b>（默认 6，范围 2-20）：颜色扩散半径（像素），越大晕染越明显</li>
 *   <li><b>paperTexture</b>（默认 0.2，范围 0.0-1.0）：纸张纹理强度</li>
 *   <li><b>softness</b>（默认 0.5，范围 0.0-1.0）：柔化强度（降低饱和度+对比度）</li>
 *   <li><b>seed</b>（默认 12345）：随机种子（控制晕染图案，固定可复现）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>水彩效果依赖随机采样，不同 seed 会产生不同晕染图案</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("watercolor")
@SpiDescribe("水彩画风格滤镜")
@Accessors(chain = true)
public class WatercolorImageFilter extends AbstractImageFilter {

    /**
     * 颜色扩散半径（像素），默认 6
     */
    private int diffusionRadius = 6;

    /**
     * 纸张纹理强度 (0.0-1.0)，默认 0.2
     */
    private double paperTexture = 0.2;

    /**
     * 柔化强度 (0.0-1.0)，默认 0.5
     */
    private double softness = 0.5;

    /**
     * 随机种子，默认 12345
     */
    private int seed = 12345;

    /**
     * 执行水彩风格滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 水彩风格图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];
        Random rnd = new Random(seed);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int orig = argb[i];

                // 第一步：随机晕染采样（沿随机方向采样扩散半径内的颜色）
                int sumR = 0, sumG = 0, sumB = 0, count = 0;
                int angle = rnd.nextInt(360);
                float rad = (float) Math.toRadians(angle);
                float fx = (float) Math.cos(rad);
                float fy = (float) Math.sin(rad);
                for (int s = 0; s <= diffusionRadius; s += 2) {
                    int sx = x + (int) (fx * s);
                    int sy = y + (int) (fy * s);
                    if (sx < 0 || sx >= w || sy < 0 || sy >= h) {
                        continue;
                    }
                    int p = argb[sy * w + sx];
                    sumR += (p >> 16) & 0xff;
                    sumG += (p >> 8) & 0xff;
                    sumB += p & 0xff;
                    count++;
                }
                int r, g, b;
                if (count > 0) {
                    r = sumR / count;
                    g = sumG / count;
                    b = sumB / count;
                } else {
                    r = (orig >> 16) & 0xff;
                    g = (orig >> 8) & 0xff;
                    b = orig & 0xff;
                }

                // 第二步：柔化（降低饱和度 + 提亮）
                int lum = (int) (r * 0.299 + g * 0.587 + b * 0.114);
                r = (int) (r * (1 - softness * 0.5) + lum * softness * 0.5);
                g = (int) (g * (1 - softness * 0.5) + lum * softness * 0.5);
                b = (int) (b * (1 - softness * 0.5) + lum * softness * 0.5);
                // 留白：亮部偏白
                if (lum > 180) {
                    int fade = (int) ((lum - 180) * 0.3 * softness);
                    r = clamp(r + fade);
                    g = clamp(g + fade);
                    b = clamp(b + fade);
                }

                // 第三步：纸张纹理（随机噪点）
                if (paperTexture > 0) {
                    int noise = (int) ((rnd.nextGaussian() - 0.5) * paperTexture * 40);
                    r = clamp(r + noise);
                    g = clamp(g + noise);
                    b = clamp(b + noise);
                }

                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
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
