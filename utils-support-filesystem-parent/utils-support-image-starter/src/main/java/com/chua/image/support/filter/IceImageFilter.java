package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 冰冻/冰雪风格滤镜
 * <p>
 * 模拟冰冻场景的视觉风格：
 * 1. 冷色调：整体偏蓝白，压暗红色
 * 2. 冰晶纹理：叠加细密的随机结晶线条
 * 3. 高光边缘：亮部偏白，模拟冰面反光
 * 4. 轻微锐化：冰雪质感清晰
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认冰冻风格
 * BufferedImage ice = new IceImageFilter().converter(src);
 *
 * // 更强冰晶
 * IceImageFilter filter = new IceImageFilter()
 *         .setIceCrystals(0.5)
 *         .setColdTone(0.7);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>coldTone</b>（默认 0.5，范围 0.0-1.0）：冷色调强度</li>
 *   <li><b>iceCrystals</b>（默认 0.3，范围 0.0-1.0）：冰晶纹理强度</li>
 *   <li><b>highlight</b>（默认 0.4，范围 0.0-1.0）：亮部高光强度</li>
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
@Spi("ice")
@SpiDescribe("冰冻冰雪风格滤镜")
@Accessors(chain = true)
public class IceImageFilter extends AbstractImageFilter {

    /**
     * 冷色调强度，默认 0.5
     */
    private double coldTone = 0.5;

    /**
     * 冰晶纹理强度，默认 0.3
     */
    private double iceCrystals = 0.3;

    /**
     * 亮部高光强度，默认 0.4
     */
    private double highlight = 0.4;

    /**
     * 执行冰冻风格滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 冰冻风格图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = argb[i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int lum = (int) (r * 0.299 + g * 0.587 + b * 0.114);

                // 冷色调：压红提蓝
                if (coldTone > 0) {
                    r = ImageProcessorUtils.clamp((int) (r * (1 - coldTone * 0.5)));
                    b = ImageProcessorUtils.clamp((int) (b * (1 + coldTone * 0.6) + coldTone * 30));
                    g = ImageProcessorUtils.clamp((int) (g * (1 + coldTone * 0.1)));
                }

                // 冰晶纹理（基于坐标的细密斜线）
                if (iceCrystals > 0) {
                    double proj = x * 0.7 + y * 0.9;
                    double phase = Math.floor(proj / 3.0);
                    boolean crystal = ((int) phase) % 3 == 0;
                    // 暗部冰晶更明显
                    if (crystal) {
                        int boost = (int) (iceCrystals * (255 - lum) * 0.4);
                        b = ImageProcessorUtils.clamp(b + boost);
                        g = ImageProcessorUtils.clamp(g + boost / 2);
                    }
                }

                // 亮部高光（偏白）
                if (highlight > 0 && lum > 160) {
                    int boost = (int) ((lum - 160) / 95.0 * highlight * 40);
                    r = ImageProcessorUtils.clamp(r + boost);
                    g = ImageProcessorUtils.clamp(g + boost);
                    b = ImageProcessorUtils.clamp(b + boost);
                }

                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }
}
