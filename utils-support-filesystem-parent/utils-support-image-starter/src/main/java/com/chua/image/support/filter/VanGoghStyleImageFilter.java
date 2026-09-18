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
 * 梵高风格滤镜
 * <p>
 * 模拟梵高《星月夜》的旋涡笔触画风：
 * 1. 方向场：基于图像梯度的角度生成笔触方向
 * 2. 短笔触：沿方向场采样邻域颜色，产生"厚涂"笔触感
 * 3. 旋涡增强：基于坐标的旋转分量，模拟天空的旋涡
 * 4. 笔触噪声：叠加少量随机噪声模拟油彩质感
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认梵高风格
 * BufferedImage vg = new VanGoghStyleImageFilter().converter(src);
 *
 * // 加强旋涡 + 更粗笔触
 * VanGoghStyleImageFilter filter = new VanGoghStyleImageFilter()
 *         .setStrokeLength(12)
 *         .setVortexStrength(0.8);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>strokeLength</b>（默认 8）：笔触长度（像素），越大笔触越明显</li>
 *   <li><b>smoothing</b>（默认 0.6，范围 0.0-1.0）：颜色平滑强度，越大越"厚涂"</li>
 *   <li><b>vortexStrength</b>（默认 0.5，范围 0.0-1.0）：旋涡强度，增强旋转感</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>笔触方向基于 Sobel 梯度，噪声多的图建议先降噪</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("vangogh")
@SpiDescribe("梵高风格滤镜")
@Accessors(chain = true)
public class VanGoghStyleImageFilter extends AbstractImageFilter {

    /**
    * 笔触长度（像素），默认 8
    */
    private int strokeLength = 8;

    /**
    * 颜色平滑强度 (0.0-1.0)，默认 0.6
    */
    private double smoothing = 0.6;

    /**
    * 旋涡强度 (0.0-1.0)，默认 0.5
    */
    private double vortexStrength = 0.5;

    /**
    * 执行梵高风格滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 梵高风格图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        Random rnd = new Random(42);

        // 第一步：计算梯度（Sobel 简化版）
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        float[] gx = new float[w * h];
        float[] gy = new float[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float c = lum(argb[i]);
                gx[i] = lum(argb[i + 1]) - lum(argb[i - 1]);
                gy[i] = lum(argb[i + w]) - lum(argb[i - w]);
            }
        }

        // 第二步：沿方向场绘制短笔触
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float angle = (float) Math.atan2(gy[i], gx[i]);
                // 旋涡：加一个坐标相关的旋转分量
                angle += (float) Math.sin(x * 0.05) * vortexStrength * 0.5f;

                // 沿方向采样 strokeLength 内的邻域，混合颜色（厚涂感）
                float dx = (float) Math.cos(angle);
                float dy = (float) Math.sin(angle);
                int sumR = 0, sumG = 0, sumB = 0, count = 0;
                for (int s = -strokeLength; s <= strokeLength; s += 2) {
                    int sx = x + (int) (dx * s);
                    int sy = y + (int) (dy * s);
                    if (sx < 0 || sx >= w || sy < 0 || sy >= h) {
                        continue;
                    }
                    int p = argb[sy * w + sx];
                    sumR += (p >> 16) & 0xff;
                    sumG += (p >> 8) & 0xff;
                    sumB += p & 0xff;
                    count++;
                }
                if (count == 0) {
                    outPixels[i] = argb[i];
                    continue;
                }
                int r = sumR / count;
                int g = sumG / count;
                int b = sumB / count;
                // 平滑：与原图混合
                int orig = argb[i];
                r = (int) ((1 - smoothing) * r + smoothing * ((orig >> 16) & 0xff));
                g = (int) ((1 - smoothing) * g + smoothing * ((orig >> 8) & 0xff));
                b = (int) ((1 - smoothing) * b + smoothing * (orig & 0xff));
                // 加少量笔触噪声（油彩质感）
                int noise = rnd.nextInt(7) - 3;
                r = clamp(r + noise);
                g = clamp(g + noise);
                b = clamp(b + noise);
                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
    * 计算像素亮度（NTSC 公式）
    *
    * @param rgb ARGB 像素值
    * @return 亮度 (0-255)
    */
    private float lum(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return r * 0.299f + g * 0.587f + b * 0.114f;
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
