package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.LookupOp;
import java.awt.image.WritableRenderedImage;


/**
 * 直方图均衡化滤镜
 * <p>
 * 通过直方图均衡化（Histogram Equalization）拉伸对比度，
 * 让图像亮度分布更均匀，突出细节。
 * 分别对 R/G/B 三通道（或灰度图）做均衡化。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认直方图均衡
 * BufferedImage eq = new HistogramEqualizeImageFilter().converter(src);
 *
 * // 只均衡灰度（结果单色）
 * HistogramEqualizeImageFilter filter = new HistogramEqualizeImageFilter()
 *         .setGrayscale(true);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>grayscale</b>（默认 false）：是否均衡为灰度图</li>
 *   <li><b>clipPercent</b>（默认 0，范围 0-50）：百分位裁剪（防止极端像素拉伸过度）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>clipPercent 越大越温和，0 = 完全均衡</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("histeq")
@SpiDescribe("直方图均衡化滤镜")
@Accessors(chain = true)
public class HistogramEqualizeImageFilter extends AbstractImageFilter {

    /**
     * 是否均衡为灰度，默认 false
     */
    private boolean grayscale = false;

    /**
     * 百分位裁剪 (0-50)，默认 0
     */
    private int clipPercent = 0;

    /**
     * 执行直方图均衡化滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 均衡化后图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        if (grayscale) {
            // 灰度均衡
            int[] hist = new int[256];
            for (int p : argb) {
                hist[ImageProcessorUtils.luminance(p)]++;
            }
            int[] lut = buildLut(hist, w * h);
            for (int i = 0; i < argb.length; i++) {
                int gray = lut[ImageProcessorUtils.luminance(argb[i])];
                outPixels[i] = (0xff << 24) | (gray << 16) | (gray << 8) | gray;
            }
        } else {
            // 三通道分别均衡
            int[] lutR = buildChannelLut(argb, 16, w * h);
            int[] lutG = buildChannelLut(argb, 8, w * h);
            int[] lutB = buildChannelLut(argb, 0, w * h);
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                int r = lutR[(p >> 16) & 0xff];
                int g = lutG[(p >> 8) & 0xff];
                int b = lutB[p & 0xff];
                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
     * 根据直方图构建查找表（支持百分位裁剪）
     *
     * @param hist  直方图（256 项）
     * @param total 像素总数
     * @return LUT
     */
    private int[] buildLut(int[] hist, int total) {
        int[] lut = new int[256];
        int clipCount = (total * clipPercent) / 100;
        int low = 0, high = 255;
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= clipCount) {
                low = i;
                break;
            }
        }
        acc = 0;
        for (int i = 255; i >= 0; i--) {
            acc += hist[i];
            if (acc >= clipCount) {
                high = i;
                break;
            }
        }
        if (high <= low) {
            high = 255;
            low = 0;
        }
        // CDF
        int[] cdf = new int[256];
        int sum = 0;
        int range = high - low;
        for (int i = 0; i < 256; i++) {
            if (i < low) {
                cdf[i] = 0;
            } else if (i > high) {
                cdf[i] = 255;
            } else {
                // 归一化到 [low, high]
                int cdfBefore = 0;
                for (int j = low; j < i; j++) {
                    cdfBefore += hist[j];
                }
                int cdfRange = 0;
                for (int j = low; j <= high; j++) {
                    cdfRange += hist[j];
                }
                cdf[i] = cdfRange == 0 ? i : (cdfBefore * 255) / cdfRange;
            }
        }
        for (int i = 0; i < 256; i++) {
            lut[i] = cdf[i];
        }
        return lut;
    }

    /**
     * 构建单通道 LUT
     *
     * @param argb  像素数组
     * @param shift 通道左移位数（16=R, 8=G, 0=B）
     * @param total 像素总数
     * @return LUT
     */
    private int[] buildChannelLut(int[] argb, int shift, int total) {
        int[] hist = new int[256];
        for (int p : argb) {
            hist[(p >> shift) & 0xff]++;
        }
        return buildLut(hist, total);
    }
}
