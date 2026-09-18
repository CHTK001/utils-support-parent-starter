package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 动漫风格滤镜
 * <p>
 * 模拟 2D 动漫/卡通风格：
 * 1. 卡通着色（cel shading）：将颜色量化为少数几个色阶，产生平涂感
 * 2. 卡通描边：基于梯度检测边缘，叠加深色描边
 * 3. 高饱和：动漫颜色比照片更鲜艳
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认动漫风格
 * BufferedImage anime = new AnimeStyleImageFilter().converter(src);
 *
 * // 更"漫画"（色阶更少 + 描边更强）
 * AnimeStyleImageFilter filter = new AnimeStyleImageFilter()
 *         .setToneLevels(3)
 *         .setOutlineStrength(0.8);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>toneLevels</b>（默认 4，范围 2-8）：卡通着色的色阶数，越少越"漫画"</li>
 *   <li><b>outlineStrength</b>（默认 0.5，范围 0.0-1.0）：描边强度</li>
 *   <li><b>outlineThreshold</b>（默认 80，范围 20-200）：描边检测阈值（梯度幅值）</li>
 *   <li><b>saturationBoost</b>（默认 1.4，范围 1.0-3.0）：饱和度增强系数</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>toneLevels=2-3 时接近漫画上色，4-6 保留更多细节</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("anime")
@SpiDescribe("动漫风格滤镜")
@Accessors(chain = true)
public class AnimeStyleImageFilter extends AbstractImageFilter {

    /**
    * 卡通着色色阶数，默认 4
    */
    private int toneLevels = 4;

    /**
    * 描边强度，默认 0.5
    */
    private double outlineStrength = 0.5;

    /**
    * 描边检测阈值，默认 80
    */
    private int outlineThreshold = 80;

    /**
    * 饱和度增强系数，默认 1.4
    */
    private double saturationBoost = 1.4;

    /**
    * 执行动漫风格滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 动漫风格图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        // 灰度（用于描边）
        int[] gray = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            gray[i] = ((p >> 16 & 0xff) * 77 + (p >> 8 & 0xff) * 151 + (p & 0xff) * 28) >> 8;
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

        int quantStep = 255 / Math.max(1, toneLevels - 1);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = argb[i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int lum = (int) (r * 0.299 + g * 0.587 + b * 0.114);

                // 饱和度增强
                r = clamp((int) (lum + (r - lum) * saturationBoost));
                g = clamp((int) (lum + (g - lum) * saturationBoost));
                b = clamp((int) (lum + (b - lum) * saturationBoost));

                // 卡通着色（颜色量化）
                r = (int) (Math.round(r / (double) quantStep) * quantStep);
                g = (int) (Math.round(g / (double) quantStep) * quantStep);
                b = (int) (Math.round(b / (double) quantStep) * quantStep);
                r = clamp(r);
                g = clamp(g);
                b = clamp(b);

                // 描边：梯度大的区域压暗
                if (outlineStrength > 0 && edge[i] > outlineThreshold) {
                    int dark = (int) (outlineStrength * Math.min(1.0, edge[i] / 255.0) * 120);
                    r = clamp(r - dark);
                    g = clamp(g - dark);
                    b = clamp(b - dark);
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
