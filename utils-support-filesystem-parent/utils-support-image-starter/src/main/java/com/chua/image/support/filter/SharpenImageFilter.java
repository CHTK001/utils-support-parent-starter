package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 锐化滤镜（非锐化掩模 USM 改进版）
 * <p>
 * 标准 USM（Unsharp Mask）：原图 + (原图 - 模糊图) × amount。
 * 本实现加入阈值保护：模糊差小于阈值的区域不锐化，避免放大噪声。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认锐化
 * BufferedImage sharp = new SharpenImageFilter().converter(src);
 *
 * // 更强锐化 + 噪声保护
 * SharpenImageFilter filter = new SharpenImageFilter()
 *         .setAmount(1.8)
 *         .setThreshold(6);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>amount</b>（默认 1.2，范围 0.0-4.0）：锐化系数</li>
 *   <li><b>radius</b>（默认 1）：模糊半径</li>
 *   <li><b>threshold</b>（默认 4，范围 0-64）：噪声保护阈值（0=不保护）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>带噪声的图建议先用 {@link DenoiseImageFilter} 降噪再锐化</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("sharpen")
@SpiDescribe("锐化滤镜(带噪声保护)")
@Accessors(chain = true)
public class SharpenImageFilter extends AbstractImageFilter {

    /**
     * 锐化系数，默认 1.2
     */
    private double amount = 1.2;

    /**
     * 模糊半径，默认 1
     */
    private int radius = 1;

    /**
     * 噪声保护阈值，默认 4
     */
    private int threshold = 4;

    /**
     * 执行锐化滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 锐化后图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];
        int r = Math.max(1, radius);

        int[] blur = boxBlur(boxBlur(argb, w, h, r), w, h, r);

        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int b = blur[i];
            int orr = (p >> 16) & 0xff, og = (p >> 8) & 0xff, ob = p & 0xff;
            int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;

            // 差值（USM 掩模）
            int dR = orr - br, dG = og - bg, dB = ob - bb;
            int maxDiff = Math.max(Math.abs(dR), Math.max(Math.abs(dG), Math.abs(dB)));

            int rr, gg, bb2;
            if (maxDiff < threshold) {
                // 小于阈值：不锐化（避免放大噪声）
                rr = orr;
                gg = og;
                bb2 = ob;
            } else {
                rr = (int) (orr + dR * amount);
                gg = (int) (og + dG * amount);
                bb2 = (int) (ob + dB * amount);
            }

            outPixels[i] = (0xff << 24)
                    | (ImageProcessorUtils.clamp(rr) << 16)
                    | (ImageProcessorUtils.clamp(gg) << 8)
                    | ImageProcessorUtils.clamp(bb2);
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
     * ARGB 像素盒模糊
     *
     * @param src 像素数组
     * @param w   宽
     * @param h   高
     * @param r   半径
     * @return 模糊后像素数组
     */
    private int[] boxBlur(int[] src, int w, int h, int r) {
        int rr = Math.max(1, r);
        int[] out = new int[w * h];
        int[] tmp = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int from = Math.max(0, x - rr);
                int to = Math.min(w - 1, x + rr);
                int sumR = 0, sumG = 0, sumB = 0, cnt = 0;
                for (int xx = from; xx <= to; xx++) {
                    int p = src[y * w + xx];
                    sumR += (p >> 16) & 0xff;
                    sumG += (p >> 8) & 0xff;
                    sumB += p & 0xff;
                    cnt++;
                }
                tmp[y * w + x] = (0xff << 24) | ((sumR / cnt) << 16) | ((sumG / cnt) << 8) | (sumB / cnt);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int from = Math.max(0, y - rr);
                int to = Math.min(h - 1, y + rr);
                int sumR = 0, sumG = 0, sumB = 0, cnt = 0;
                for (int yy = from; yy <= to; yy++) {
                    int p = tmp[yy * w + x];
                    sumR += (p >> 16) & 0xff;
                    sumG += (p >> 8) & 0xff;
                    sumB += p & 0xff;
                    cnt++;
                }
                out[y * w + x] = (0xff << 24) | ((sumR / cnt) << 16) | ((sumG / cnt) << 8) | (sumB / cnt);
            }
        }
        return out;
    }
}
