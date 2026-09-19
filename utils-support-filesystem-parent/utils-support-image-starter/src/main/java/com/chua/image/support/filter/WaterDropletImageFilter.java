package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 水滴透镜风格滤镜
 * <p>
 * 模拟水滴落在画面上的透镜效果（类似 lens/macro 摄影）：
 * 1. 在图像上放置多个圆形"水滴"，每个水滴内的区域被放大
 * 2. 水滴边缘加高光+阴影，模拟玻璃透镜的立体感
 * 3. 水滴内的颜色略偏蓝+增饱和，模拟玻璃折射
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认水滴效果
 * BufferedImage drop = new WaterDropletImageFilter().converter(src);
 *
 * // 更少更大的水滴
 * WaterDropletImageFilter filter = new WaterDropletImageFilter()
 *         .setDropCount(12)
 *         .setDropRadius(40);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>dropCount</b>（默认 30）：水滴数量</li>
 *   <li><b>dropRadius</b>（默认 24，范围 8-128）：水滴半径（像素）</li>
 *   <li><b>zoomFactor</b>（默认 1.6，范围 1.0-3.0）：水滴内放大倍数</li>
 *   <li><b>highlight</b>（默认 0.6，范围 0.0-1.0）：水滴高光强度</li>
 *   <li><b>seed</b>（默认 777）：随机种子（固定可复现）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>水滴数量越多画面越"碎"，建议 10-50 之间</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("waterdroplet")
@SpiDescribe("水滴透镜风格滤镜")
@Accessors(chain = true)
public class WaterDropletImageFilter extends AbstractImageFilter {

    /**
     * 水滴数量，默认 30
     */
    private int dropCount = 30;

    /**
     * 水滴半径（像素），默认 24
     */
    private int dropRadius = 24;

    /**
     * 水滴内放大倍数，默认 1.6
     */
    private double zoomFactor = 1.6;

    /**
     * 高光强度，默认 0.6
     */
    private double highlight = 0.6;

    /**
     * 随机种子，默认 777
     */
    private int seed = 777;

    /**
     * 执行水滴透镜滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 水滴效果图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = argb.clone(); // 先复制原图

        java.util.Random rnd = new java.util.Random(seed);

        for (int d = 0; d < dropCount; d++) {
            // 随机水滴中心
            int cx = rnd.nextInt(w);
            int cy = rnd.nextInt(h);
            int r = dropRadius;
            if (r <= 0) {
                r = 24;
            }
            r = Math.max(4, Math.min(r, Math.min(w, h) / 4));

            for (int y = cy - r; y <= cy + r; y++) {
                if (y < 0 || y >= h) {
                    continue;
                }
                for (int x = cx - r; x <= cx + r; x++) {
                    if (x < 0 || x >= w) {
                        continue;
                    }
                    int dx = x - cx;
                    int dy = y - cy;
                    int dist2 = dx * dx + dy * dy;
                    int r2 = r * r;
                    if (dist2 > r2) {
                        continue;
                    }
                    double dist = Math.sqrt(dist2);
                    double norm = dist / r; // 0-1

                    // 透镜放大：从中心采样（zoomFactor 倍）
                    int sx = cx - (int) ((x - cx) / zoomFactor);
                    int sy = cy - (int) ((y - cy) / zoomFactor);
                    if (sx < 0) {
                        sx = 0;
                    }
                    if (sy < 0) {
                        sy = 0;
                    }
                    if (sx >= w) {
                        sx = w - 1;
                    }
                    if (sy >= h) {
                        sy = h - 1;
                    }
                    int centerPx = argb[sy * w + sx];

                    // 边缘：混合原图+中心采样（越靠近边缘越像原图）
                    double blend = 1.0 - norm * 0.4; // 中心完全用放大采样
                    int i = y * w + x;
                    int or_ = (argb[i] >> 16) & 0xff;
                    int og = (argb[i] >> 8) & 0xff;
                    int ob = argb[i] & 0xff;
                    int cr = (centerPx >> 16) & 0xff;
                    int cg = (centerPx >> 8) & 0xff;
                    int cb = centerPx & 0xff;
                    int r2v = clamp((int) (or_ * (1 - blend) + cr * blend));
                    int g2v = clamp((int) (og * (1 - blend) + cg * blend));
                    int b2v = clamp((int) (ob * (1 - blend) + cb * blend));

                    // 蓝色偏移（玻璃折射感）
                    b2v = clamp(b2v + (int) (6 * (1 - norm)));

                    // 边缘高光（靠近水滴边沿 + 左上方时变亮）
                    if (highlight > 0 && norm > 0.75) {
                        double edgeGlow = (norm - 0.75) / 0.25; // 0-1
                        int lightBoost = (int) (highlight * edgeGlow * 60);
                        r2v = clamp(r2v + lightBoost / 2);
                        g2v = clamp(g2v + lightBoost / 2);
                        b2v = clamp(b2v + lightBoost);
                    }

                    outPixels[i] = (0xff << 24) | (r2v << 16) | (g2v << 8) | b2v;
                }
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
