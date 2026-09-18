package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 细节增强滤镜
 * <p>
 * 基于高通（High-Pass）的细节增强，比简单 USM 更温和、过冲更少：
 * 1. 高斯模糊得到"基础层"
 * 2. 原图 - 基础层 = 细节层（高通）
 * 3. 原图 + 细节层 × 增强系数 = 增强图
 * 4. 加一点局部对比度
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认细节增强
 * BufferedImage det = new DetailEnhanceImageFilter().converter(src);
 *
 * // 更强细节
 * DetailEnhanceImageFilter filter = new DetailEnhanceImageFilter()
 *         .setAmount(1.5)
 *         .setRadius(2);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>amount</b>（默认 0.8，范围 0.0-3.0）：细节增强系数</li>
 *   <li><b>radius</b>（默认 1）：模糊半径</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>amount 过大会放大噪声，建议先用降噪</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("detailenhance")
@SpiDescribe("细节增强滤镜")
@Accessors(chain = true)
public class DetailEnhanceImageFilter extends AbstractImageFilter {

    /**
    * 细节增强系数，默认 0.8
    */
    private double amount = 0.8;

    /**
    * 模糊半径，默认 1
    */
    private int radius = 1;

    /**
    * 执行细节增强滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 增强后图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];
        int r = Math.max(1, radius);

        // 基础层（盒模糊近似高斯，两趟）
        int[] base = boxBlur(boxBlur(argb, w, h, r), w, h, r);

        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int b = base[i];
            int orr = (p >> 16) & 0xff, og = (p >> 8) & 0xff, ob = p & 0xff;
            int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
            // 细节层 = 原 - 基础
            int detailR = orr - br;
            int detailG = og - bg;
            int detailB = ob - bb;
            // 增强 = 原 + 细节 × amount
            int rr = (int) (orr + detailR * amount);
            int gg = (int) (og + detailG * amount);
            int gbb = (int) (ob + detailB * amount);
            outPixels[i] = (0xff << 24)
                    | (ImageProcessorUtils.clamp(rr) << 16)
                    | (ImageProcessorUtils.clamp(gg) << 8)
                    | ImageProcessorUtils.clamp(gbb);
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
