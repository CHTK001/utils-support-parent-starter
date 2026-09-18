package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 人像磨皮滤镜
 * <p>
 * 选择性平滑（Selective Smoothing）实现磨皮效果：
 * 边缘处保留细节（皮肤纹理/五官轮廓），平滑处（皮肤）做局部模糊，
 * 再与原图按"平滑度"混合，达到"磨皮不破脸"的效果。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认磨皮
 * BufferedImage skin = new FaceEnhanceImageFilter().converter(src);
 *
 * // 更强磨皮
 * FaceEnhanceImageFilter filter = new FaceEnhanceImageFilter()
 *         .setStrength(0.7)
 *         .setRadius(3);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>strength</b>（默认 0.4，范围 0.0-1.0）：磨皮强度</li>
 *   <li><b>radius</b>（默认 2）：平滑邻域半径</li>
 *   <li><b>edgeThreshold</b>（默认 30，范围 0-255）：边缘判定阈值（梯度幅值），越大越不磨边缘</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>全图磨皮（不分人脸区域），非人脸图效果一般</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("faceenhance")
@SpiDescribe("人像磨皮滤镜")
@Accessors(chain = true)
public class FaceEnhanceImageFilter extends AbstractImageFilter {

    /**
    * 磨皮强度，默认 0.4
    */
    private double strength = 0.4;

    /**
    * 平滑邻域半径，默认 2
    */
    private int radius = 2;

    /**
    * 边缘判定阈值，默认 30
    */
    private int edgeThreshold = 30;

    /**
    * 执行磨皮滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 磨皮后图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];
        int r = Math.max(1, radius);

        // 平滑图（盒模糊两趟）
        int[] smooth = boxBlur(boxBlur(argb, w, h, r), w, h, r);

        // 边缘图（Sobel 幅度）
        int[] gray = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            gray[i] = ImageProcessorUtils.luminance(argb[i]);
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = argb[i];
                int s = smooth[i];
                // 简单边缘：与原图差的绝对值大 = 边缘
                int diff = Math.abs(((s >> 16) & 0xff) - ((p >> 16) & 0xff))
                        + Math.abs(((s >> 8) & 0xff) - ((p >> 8) & 0xff))
                        + Math.abs((s & 0xff) - (p & 0xff));
                double t = Math.min(1.0, strength * (1.0 - (double) diff / (edgeThreshold * 3.0)));
                int orr = (p >> 16) & 0xff, og = (p >> 8) & 0xff, ob = p & 0xff;
                int sr = (s >> 16) & 0xff, sg = (s >> 8) & 0xff, sb = s & 0xff;
                int rr = (int) (orr + (sr - orr) * t);
                int gg = (int) (og + (sg - og) * t);
                int bb = (int) (ob + (sb - ob) * t);
                outPixels[i] = (0xff << 24)
                        | (ImageProcessorUtils.clamp(rr) << 16)
                        | (ImageProcessorUtils.clamp(gg) << 8)
                        | ImageProcessorUtils.clamp(bb);
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
    * ARGB 像素盒模糊
    *
    * @param src  像素数组
    * @param w    宽
    * @param h    高
    * @param r    半径
    * @return 模糊后的像素数组
    */
    private int[] boxBlur(int[] src, int w, int h, int r) {
        int rr = Math.max(1, r);
        int[] out = new int[w * h];
        // 水平
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
        // 垂直
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
