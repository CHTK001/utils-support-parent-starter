package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * 胶片颗粒滤镜
 * <p>
 * 叠加胶片颗粒（film grain）质感：
 * 1. 随机高斯噪声（按强度）：模拟胶片乳剂颗粒
 * 2. 轻微暖色调：胶片通常偏暖
 * 3. 对比度轻微 S 曲线：模拟胶片色调响应
 * 4. 可选边缘暗角：胶片画幅的暗角感
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认胶片颗粒
 * BufferedImage film = new FilmGrainImageFilter().converter(src);
 *
 * // 重颗粒 + 更暖
 * FilmGrainImageFilter filter = new FilmGrainImageFilter()
 *         .setGrainStrength(0.35)
 *         .setWarmTone(0.3);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>grainStrength</b>（默认 0.15，范围 0.0-1.0）：颗粒强度</li>
 *   <li><b>grainSize</b>（默认 1，范围 1-8）：颗粒尺寸（1=逐像素，&gt;1=分块放大，更像粗颗粒）</li>
 *   <li><b>warmTone</b>（默认 0.15，范围 0.0-1.0）：暖色调强度</li>
 *   <li><b>vignette</b>（默认 0.2，范围 0.0-1.0）：边缘暗角强度（0 关闭）</li>
 *   <li><b>seed</b>（默认 555）：随机种子</li>
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
@Spi("filmgrain")
@SpiDescribe("胶片颗粒滤镜")
@Accessors(chain = true)
public class FilmGrainImageFilter extends AbstractImageFilter {

    /**
    * 颗粒强度，默认 0.15
    */
    private double grainStrength = 0.15;

    /**
    * 颗粒尺寸，默认 1
    */
    private int grainSize = 1;

    /**
    * 暖色调强度，默认 0.15
    */
    private double warmTone = 0.15;

    /**
    * 边缘暗角强度，默认 0.2
    */
    private double vignette = 0.2;

    /**
    * 随机种子，默认 555
    */
    private int seed = 555;

    /**
    * 执行胶片颗粒滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 胶片颗粒效果图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];
        Random rnd = new Random(seed);
        int gs = Math.max(1, grainSize);
        float maxDist = (float) Math.hypot(w / 2f, h / 2f);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = argb[i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;

                // S 曲线（轻微对比）
                r = (int) (255 * Math.pow(r / 255.0, 0.95));
                g = (int) (255 * Math.pow(g / 255.0, 0.95));
                b = (int) (255 * Math.pow(b / 255.0, 0.95));

                // 暖色调
                if (warmTone > 0) {
                    r = ImageProcessorUtils.clamp(r + (int) (warmTone * 18));
                    g = ImageProcessorUtils.clamp(g + (int) (warmTone * 6));
                    b = ImageProcessorUtils.clamp(b - (int) (warmTone * 12));
                }

                // 颗粒（分块：同块内共享噪声，模拟粗颗粒）
                if (grainStrength > 0) {
                    int n = (int) ((rnd.nextGaussian()) * grainStrength * 50);
                    r = ImageProcessorUtils.clamp(r + n);
                    g = ImageProcessorUtils.clamp(g + n);
                    b = ImageProcessorUtils.clamp(b + n);
                }

                // 暗角
                if (vignette > 0) {
                    float dist = (float) Math.hypot(x - w / 2f, y - h / 2f);
                    float t = Math.max(0, dist - maxDist * 0.5f) / (maxDist * 0.5f);
                    float keep = 1f - (float) Math.pow(t, 1.5f) * (float) vignette;
                    r = ImageProcessorUtils.clamp((int) (r * keep));
                    g = ImageProcessorUtils.clamp((int) (g * keep));
                    b = ImageProcessorUtils.clamp((int) (b * keep));
                }

                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }
}
