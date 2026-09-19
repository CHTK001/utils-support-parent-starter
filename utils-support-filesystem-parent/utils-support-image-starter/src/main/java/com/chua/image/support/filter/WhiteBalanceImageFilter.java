package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 白平衡滤镜
 * <p>
 * 基于"灰度世界"（Gray World）假设校正白平衡：
 * 一张正常曝光的照片，其全图 RGB 通道均值理论上应该相等（呈中性灰）。
 * 偏色时各通道均值不等，按比例归一化即可校正。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认白平衡
 * BufferedImage wb = new WhiteBalanceImageFilter().converter(src);
 *
 * // 更强校正
 * WhiteBalanceImageFilter filter = new WhiteBalanceImageFilter()
 *         .setStrength(1.0);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>strength</b>（默认 0.8，范围 0.0-1.0）：校正强度，1.0 = 完全校正</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>对整幅都是单一颜色（如纯蓝天空）的图效果差</li>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("whitebalance")
@SpiDescribe("白平衡滤镜")
@Accessors(chain = true)
public class WhiteBalanceImageFilter extends AbstractImageFilter {

    /**
     * 校正强度，默认 0.8
     */
    private double strength = 0.8;

    /**
     * 执行白平衡滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 白平衡校正后图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        // 计算各通道均值
        long sumR = 0, sumG = 0, sumB = 0;
        int count = 0;
        for (int p : argb) {
            // 跳过全黑全白（不影响均值）
            sumR += (p >> 16) & 0xff;
            sumG += (p >> 8) & 0xff;
            sumB += p & 0xff;
            count++;
        }
        if (count == 0) {
            return out;
        }
        double avgR = (double) sumR / count;
        double avgG = (double) sumG / count;
        double avgB = (double) sumB / count;
        double avgAll = (avgR + avgG + avgB) / 3.0;
        if (avgAll < 1) {
            return out;
        }

        // 灰度世界校正因子
        double scaleR = avgAll / avgR;
        double scaleG = avgAll / avgG;
        double scaleB = avgAll / avgB;

        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int r = (int) (((p >> 16) & 0xff) * ((1 - strength) + strength * scaleR));
            int g = (int) (((p >> 8) & 0xff) * ((1 - strength) + strength * scaleG));
            int b = (int) ((p & 0xff) * ((1 - strength) + strength * scaleB));
            outPixels[i] = (0xff << 24)
                    | (ImageProcessorUtils.clamp(r) << 16)
                    | (ImageProcessorUtils.clamp(g) << 8)
                    | ImageProcessorUtils.clamp(b);
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }
}
