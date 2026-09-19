package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * HDR 局部对比度增强滤镜
 * <p>
 * 模拟 HDR 效果的局部对比度增强（Local Tone Mapping）：
 * 1. 计算亮度图（NTSC 灰度）
 * 2. 用不同半径的高斯模糊得到"局部背景亮度"
 * 3. 局部对比度 = 原亮度 - 背景亮度，放大后叠加回原图
 * 4. 全局轻微对比度 + 提亮暗部（模拟 HDR 的亮部不过曝、暗部有细节）
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认 HDR 增强
 * BufferedImage hdr = new HDREnhanceImageFilter().converter(src);
 *
 * // 更强的局部对比 + 提亮暗部
 * HDREnhanceImageFilter filter = new HDREnhanceImageFilter()
 *         .setLocalStrength(2.0)
 *         .setShadowLift(0.3);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>localStrength</b>（默认 1.2，范围 0.0-5.0）：局部对比度增强系数</li>
 *   <li><b>shadowLift</b>（默认 0.2，范围 0.0-1.0）：暗部提亮强度</li>
 *   <li><b>radius</b>（默认 24，范围 4-128）：局部背景模糊半径（像素）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>radius 越大，局部范围越大，对比度越"全局化"</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("hdenhance")
@SpiDescribe("HDR局部对比度增强滤镜")
@Accessors(chain = true)
public class HDREnhanceImageFilter extends AbstractImageFilter {

    /**
     * 局部对比度增强系数，默认 1.2
     */
    private double localStrength = 1.2;

    /**
     * 暗部提亮强度，默认 0.2
     */
    private double shadowLift = 0.2;

    /**
     * 局部背景模糊半径（像素），默认 24
     */
    private int radius = 24;

    /**
     * 执行 HDR 增强滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return HDR 增强后图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        // 亮度图
        int[] lum = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            lum[i] = ImageProcessorUtils.luminance(argb[i]);
        }

        // 局部背景：大半径高斯（分次盒模糊近似）
        int[] bg = boxBlur(boxBlur(lum, w, h, radius), w, h, radius);

        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff;
            int b = p & 0xff;
            int l = lum[i];
            int bgL = bg[i];

            // 局部对比度：(l - bg) 放大
            int localContrast = (int) ((l - bgL) * localStrength);
            // 暗部提亮：亮度越低提亮越多
            int shadow = 0;
            if (shadowLift > 0 && l < 128) {
                shadow = (int) ((128 - l) / 128.0 * shadowLift * 80);
            }
            int add = localContrast + shadow;
            // 按比例分配到 RGB（保持色调）
            int scale = add;
            outPixels[i] = (0xff << 24)
                    | (ImageProcessorUtils.clamp(r + scale) << 16)
                    | (ImageProcessorUtils.clamp(g + scale) << 8)
                    | ImageProcessorUtils.clamp(b + scale);
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
     * 盒模糊（水平 + 垂直两次 1D）
     *
     * @param src   亮度图
     * @param w     宽
     * @param h     高
     * @param radius 半径
     * @return 模糊后的亮度图
     */
    private int[] boxBlur(int[] src, int w, int h, int radius) {
        int r = Math.max(1, radius);
        int[] tmp = new int[w * h];
        int[] out = new int[w * h];
        // 水平
        for (int y = 0; y < h; y++) {
            int sum = 0;
            for (int x = 0; x < w; x++) {
                int from = Math.max(0, x - r);
                int to = Math.min(w - 1, x + r);
                // 简单滑动窗口（非最优但清晰）
                sum = 0;
                int cnt = 0;
                for (int xx = from; xx <= to; xx++) {
                    sum += src[y * w + xx];
                    cnt++;
                }
                tmp[y * w + x] = sum / cnt;
            }
        }
        // 垂直
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int from = Math.max(0, y - r);
                int to = Math.min(h - 1, y + r);
                int sum = 0, cnt = 0;
                for (int yy = from; yy <= to; yy++) {
                    sum += tmp[yy * w + x];
                    cnt++;
                }
                out[y * w + x] = sum / cnt;
            }
        }
        return out;
    }
}
