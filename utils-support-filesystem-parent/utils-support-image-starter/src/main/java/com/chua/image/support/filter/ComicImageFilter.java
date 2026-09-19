package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;


/**
 * 美漫风格滤镜
 * <p>
 * 模拟美式漫画（comic）风格：
 * 1. 颜色色块化：把连续颜色压缩到少数色阶
 * 2. 网点（Halftone）：在中间调叠加 Ben-Day 网点，模拟印刷
 * 3. 粗描边：Sobel 梯度阈值化，画粗黑边
 * 4. 高饱和+高对比：漫画颜色鲜艳
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认美漫风格
 * BufferedImage comic = new ComicImageFilter().converter(src);
 *
 * // 更"漫画"（色阶更少 + 网点更密）
 * ComicImageFilter filter = new ComicImageFilter()
 *         .setToneLevels(3)
 *         .setHalftoneSize(3);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>toneLevels</b>（默认 4，范围 2-8）：颜色色阶数，越少越"漫画"</li>
 *   <li><b>halftone</b>（默认 true）：是否叠加网点</li>
 *   <li><b>halftoneSize</b>（默认 4）：网点间距（像素）</li>
 *   <li><b>outlineThreshold</b>（默认 100，范围 20-255）：描边检测阈值（梯度幅值）</li>
 *   <li><b>saturationBoost</b>（默认 1.6，范围 1.0-3.0）：饱和度增强系数</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("comic")
@SpiDescribe("美漫风格滤镜")
@Accessors(chain = true)
public class ComicImageFilter extends AbstractImageFilter {

    /**
     * 颜色色阶数，默认 4
     */
    private int toneLevels = 4;

    /**
     * 是否叠加网点，默认 true
     */
    private boolean halftone = true;

    /**
     * 网点间距（像素），默认 4
     */
    private int halftoneSize = 4;

    /**
     * 描边检测阈值，默认 100
     */
    private int outlineThreshold = 100;

    /**
     * 饱和度增强系数，默认 1.6
     */
    private double saturationBoost = 1.6;

    /**
     * 执行美漫风格滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 美漫风格图像
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
            gray[i] = ImageProcessorUtils.luminance(argb[i]);
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
                r = ImageProcessorUtils.clamp((int) (lum + (r - lum) * saturationBoost));
                g = ImageProcessorUtils.clamp((int) (lum + (g - lum) * saturationBoost));
                b = ImageProcessorUtils.clamp((int) (lum + (b - lum) * saturationBoost));

                // 色块化
                r = (int) (Math.round(r / (double) quantStep) * quantStep);
                g = (int) (Math.round(g / (double) quantStep) * quantStep);
                b = (int) (Math.round(b / (double) quantStep) * quantStep);
                r = ImageProcessorUtils.clamp(r);
                g = ImageProcessorUtils.clamp(g);
                b = ImageProcessorUtils.clamp(b);

                // 网点（中间调叠加）
                if (halftone && halftoneSize > 0 && lum > 60 && lum < 200) {
                    int dx = x % halftoneSize - halftoneSize / 2;
                    int dy = y % halftoneSize - halftoneSize / 2;
                    int dist2 = dx * dx + dy * dy;
                    int radius2 = (halftoneSize / 2 - 1) * (halftoneSize / 2 - 1);
                    if (dist2 > radius2) {
                        int dark = (255 - lum) / 6;
                        r = ImageProcessorUtils.clamp(r - dark);
                        g = ImageProcessorUtils.clamp(g - dark);
                        b = ImageProcessorUtils.clamp(b - dark);
                    }
                }

                // 粗描边
                if (edge[i] > outlineThreshold) {
                    int dark = (int) (Math.min(1.0, edge[i] / 255.0) * 90);
                    r = ImageProcessorUtils.clamp(r - dark);
                    g = ImageProcessorUtils.clamp(g - dark);
                    b = ImageProcessorUtils.clamp(b - dark);
                }

                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }
}
