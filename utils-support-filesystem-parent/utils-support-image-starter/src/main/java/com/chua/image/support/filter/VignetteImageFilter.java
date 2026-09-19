package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 暗角滤镜
 * <p>
 * 在图像四周压暗，突出中心区域（摄影中常见的镜头暗角效果）。
 * 使用径向距离计算，中心保持原色，边缘逐渐变暗。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认暗角
 * BufferedImage vig = new VignetteImageFilter().converter(src);
 *
 * // 更强暗角 + 更小的中心保护区
 * VignetteImageFilter filter = new VignetteImageFilter()
 *         .setStrength(0.8)
 *         .setInnerRadius(0.4);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>strength</b>（默认 0.5，范围 0.0-1.0）：暗角强度，1.0 = 边缘全黑</li>
 *   <li><b>innerRadius</b>（默认 0.6，范围 0.0-1.0）：中心保护区半径占比（对角线）</li>
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
@Spi("vignette")
@SpiDescribe("暗角滤镜")
@Accessors(chain = true)
public class VignetteImageFilter extends AbstractImageFilter {

    /**
     * 暗角强度，默认 0.5
     */
    private double strength = 0.5;

    /**
     * 中心保护区半径占比，默认 0.6
     */
    private double innerRadius = 0.6;

    /**
     * 执行暗角滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 暗角效果图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        float cx = w / 2f;
        float cy = h / 2f;
        float maxDist = (float) Math.hypot(cx, cy);
        float inner = maxDist * (float) innerRadius;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = argb[i];
                float dist = (float) Math.hypot(x - cx, y - cy);
                // 0 到 1 的暗角因子：inner 以内为 0（不暗），外缘为 1
                float t = (dist - inner) / (maxDist - inner);
                if (t < 0) {
                    t = 0;
                }
                if (t > 1) {
                    t = 1;
                }
                // 二次曲线让暗角更自然
                float darken = (float) Math.pow(t, 1.5) * (float) strength;
                float keep = 1.0f - darken;

                int r = ImageProcessorUtils.clamp((int) (((p >> 16) & 0xff) * keep));
                int g = ImageProcessorUtils.clamp((int) (((p >> 8) & 0xff) * keep));
                int b = ImageProcessorUtils.clamp((int) ((p & 0xff) * keep));
                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }
}
