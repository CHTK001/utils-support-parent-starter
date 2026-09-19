package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;
import java.util.Arrays;


/**
 * 降噪滤镜（中值 + 双边简化）
 * <p>
 * 两种降噪模式：
 * 1. MEDIAN（中值滤波）：去除椒盐噪点，保边缘
 * 2. BILATERAL（双边简化）：保边平滑，去高斯噪声
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 中值降噪（3x3）
 * BufferedImage den = new DenoiseImageFilter().converter(src);
 *
 * // 双边降噪（保边）
 * DenoiseImageFilter filter = new DenoiseImageFilter()
 *         .setMode(Mode.BILATERAL)
 *         .setRadius(3);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>mode</b>（默认 MEDIAN）：降噪模式</li>
 *   <li><b>radius</b>（默认 1）：邻域半径（3x3=1, 5x5=2）</li>
 *   <li><b>sigmaColor</b>（默认 25，双边模式）：颜色域标准差</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>radius 越大降噪越强，但细节损失越多</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("denoise")
@SpiDescribe("降噪滤镜")
@Accessors(chain = true)
public class DenoiseImageFilter extends AbstractImageFilter {

    /**
     * 降噪模式
     */
    public enum Mode {
        /** 中值滤波 */
        MEDIAN,
        /** 双边简化 */
        BILATERAL
    }

    /**
     * 降噪模式，默认 MEDIAN
     */
    private Mode mode = Mode.MEDIAN;

    /**
     * 邻域半径，默认 1
     */
    private int radius = 1;

    /**
     * 颜色域标准差（双边模式），默认 25
     */
    private int sigmaColor = 25;

    /**
     * 执行降噪滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 降噪后图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];
        int r = Math.max(1, radius);

        if (mode == Mode.MEDIAN) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (x < r || x >= w - r || y < r || y >= h - r) {
                        outPixels[i] = argb[i];
                        continue;
                    }
                    int[] neigh = new int[(2 * r + 1) * (2 * r + 1)];
                    int idx = 0;
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dx = -r; dx <= r; dx++) {
                            neigh[idx++] = argb[(y + dy) * w + (x + dx)];
                        }
                    }
                    Arrays.sort(neigh);
                    // 取中值（三通道分别取中位）
                    int mid = neigh[neigh.length / 2];
                    // 保留原 alpha
                    outPixels[i] = (0xff << 24) | (mid & 0x00FFFFFF);
                }
            }
        } else {
            // 双边：加权平均（颜色权重 × 空间权重）
            double sigmaSpace = r * 1.2;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (x < r || x >= w - r || y < r || y >= h - r) {
                        outPixels[i] = argb[i];
                        continue;
                    }
                    int cr = (argb[i] >> 16) & 0xff;
                    int cg = (argb[i] >> 8) & 0xff;
                    int cb = argb[i] & 0xff;
                    int sumR = 0, sumG = 0, sumB = 0;
                    double wSum = 0;
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dx = -r; dx <= r; dx++) {
                            int ni = (y + dy) * w + (x + dx);
                            int nr = (argb[ni] >> 16) & 0xff;
                            int ng = (argb[ni] >> 8) & 0xff;
                            int nb = argb[ni] & 0xff;
                            int dr = cr - nr, dg = cg - ng, db = cb - nb;
                            double colorDist = dr * dr + dg * dg + db * db;
                            double spaceDist = dx * dx + dy * dy;
                            double wgt = Math.exp(-spaceDist / (2 * sigmaSpace * sigmaSpace))
                                    * Math.exp(-colorDist / (2 * (double) sigmaColor * sigmaColor));
                            sumR += (int) (nr * wgt);
                            sumG += (int) (ng * wgt);
                            sumB += (int) (nb * wgt);
                            wSum += wgt;
                        }
                    }
                    if (wSum < 1e-6) {
                        outPixels[i] = argb[i];
                        continue;
                    }
                    int rr = (int) (sumR / wSum);
                    int gg = (int) (sumG / wSum);
                    int bb = (int) (sumB / wSum);
                    outPixels[i] = (0xff << 24)
                            | (ImageProcessorUtils.clamp(rr) << 16)
                            | (ImageProcessorUtils.clamp(gg) << 8)
                            | ImageProcessorUtils.clamp(bb);
                }
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }
}
