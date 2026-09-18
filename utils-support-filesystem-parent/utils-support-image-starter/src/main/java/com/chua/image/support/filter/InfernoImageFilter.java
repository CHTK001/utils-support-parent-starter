package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 火焰/岩浆（Inferno）色带滤镜
 * <p>
 * 把图像亮度映射到 matplotlib 的 inferno 色带（黑→紫→红→橙→黄→白），
 * 用于强调温度/强度梯度，比热成像更"火烈"。
 * 1. 取亮度作为强度
 * 2. 对比度拉伸
 * 3. 映射到 inferno 色带
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认 inferno 色带
 * BufferedImage inf = new InfernoImageFilter().converter(src);
 *
 * // 更强拉伸
 * InfernoImageFilter filter = new InfernoImageFilter()
 *         .setContrast(2.0);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>contrast</b>（默认 1.5，范围 1.0-4.0）：强度对比度（拉伸）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>结果单色映射（inferno 色带），彩色信息丢失</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("inferno")
@SpiDescribe("火焰岩浆色带滤镜")
@Accessors(chain = true)
public class InfernoImageFilter extends AbstractImageFilter {

    /**
    * 强度对比度，默认 1.5
    */
    private double contrast = 1.5;

    /**
    * Inferno 色带关键采样点（matplotlib 近似）
    * <p>索引 0, 0.25, 0.5, 0.75, 1.0 对应的 RGB
    */
    private static final int[][] INFERNO_STOPS = {
            {0, 0, 4},       // 0.00 近黑蓝
            {40, 11, 84},    // 0.25 深紫
            {109, 40, 130},  // 0.50 紫
            {179, 75, 91},   // 0.75 红紫
            {252, 141, 89},  // 1.00 橙黄
    };

    /**
    * 执行 inferno 色带滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return inferno 效果图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        for (int i = 0; i < argb.length; i++) {
            int gray = ImageProcessorUtils.luminance(argb[i]);
            int v = (int) (((gray - 128) * contrast + 128) / 255.0 * 255);
            int t = ImageProcessorUtils.clamp(v);
            int[] rgb = sampleInferno(t);
            outPixels[i] = (0xff << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
    * 在 inferno 色带采样（5 个控制点线性插值）
    *
    * @param t 0-255
    * @return [r, g, b]
    */
    private int[] sampleInferno(int t) {
        double x = t / 255.0 * 4.0; // 0-4
        int idx = (int) Math.floor(x);
        if (idx >= 4) {
            idx = 3;
        }
        if (idx < 0) {
            idx = 0;
        }
        double f = x - idx;
        int[] a = INFERNO_STOPS[idx];
        int[] b = INFERNO_STOPS[idx + 1];
        int r = (int) (a[0] + (b[0] - a[0]) * f);
        int g = (int) (a[1] + (b[1] - a[1]) * f);
        int bl = (int) (a[2] + (b[2] - a[2]) * f);
        return new int[]{
                ImageProcessorUtils.clamp(r),
                ImageProcessorUtils.clamp(g),
                ImageProcessorUtils.clamp(bl)
        };
    }
}
