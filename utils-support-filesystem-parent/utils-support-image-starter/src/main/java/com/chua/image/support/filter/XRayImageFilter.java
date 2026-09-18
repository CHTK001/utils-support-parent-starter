package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * X光效果滤镜
 * <p>
 * 模拟医学 X 光的观感：
 * 1. 反色：亮的变暗、暗的变亮（X 光片特征）
 * 2. 灰度化：转为单色（X 光是灰度）
 * 3. 高对比：X 光片对比极强
 * 4. 蓝调染色：模拟荧光蓝的 X 光屏
 * 5. 暗部压黑 + 亮部略降（模拟 X 光透射）
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认 X 光效果
 * BufferedImage xr = new XRayImageFilter().converter(src);
 *
 * // 更强蓝调
 * XRayImageFilter filter = new XRayImageFilter()
 *         .setBlueTint(0.5)
 *         .setContrast(2.0);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>blueTint</b>（默认 0.3，范围 0.0-1.0）：蓝色染色强度</li>
 *   <li><b>contrast</b>（默认 1.6，范围 1.0-4.0）：对比度增强系数</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>结果接近单色蓝白，彩色信息全部丢失</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("xray")
@SpiDescribe("X光效果滤镜")
@Accessors(chain = true)
public class XRayImageFilter extends AbstractImageFilter {

    /**
    * 蓝色染色强度，默认 0.3
    */
    private double blueTint = 0.3;

    /**
    * 对比度增强系数，默认 1.6
    */
    private double contrast = 1.6;

    /**
    * 执行 X 光滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return X 光效果图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            // 灰度
            int gray = ImageProcessorUtils.luminance(p);
            // 反色
            int inv = 255 - gray;
            // 高对比（以 128 为轴）
            int c = (int) (((inv - 128) * contrast + 128));
            c = ImageProcessorUtils.clamp(c);

            // 蓝调：B 通道偏亮、R 通道偏暗
            int r = ImageProcessorUtils.clamp((int) (c * (1 - blueTone())));
            int g = c;
            int b = ImageProcessorUtils.clamp(c + (int) (blueTint * 40));
            outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
    * 红色通道衰减系数（蓝色调越浓，R 越弱）
    *
    * @return 0.0-1.0
    */
    private double blueTone() {
        return blueTint * 0.5;
    }
}
