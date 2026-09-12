package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * USM (Unsharp Mask) 锐化滤镜
 *
 * USM 是一种经典的图像锐化技术，通过从原图像中减去模糊版本来增强边缘和细节。
 * 该技术最初来源于传统摄影的暗房技术，现在广泛应用于数字图像处理。
 *
 * 技术原理：
 * - 创建原图像的高斯模糊版本
 * - 计算原图像与模糊图像的差值（反锐化掩模）
 * - 将差值按权重加回原图像
 * - 公式：锐化图像 = 原图像 + 权重 × (原图像 - 模糊图像)
 *
 * 算法流程：
 * 1. 对原图像应用高斯模糊
 * 2. 计算原图像与模糊图像的像素差值
 * 3. 将差值乘以权重系数
 * 4. 将加权差值添加到原图像
 *
 * 参数说明：
   * - 权重: 锐化强度权重，范围通常为0.1-2.0
 *   - 0.1-0.5: 轻微锐化，适合人像
 *   - 0.5-1.0: 中等锐化，适合一般照片
 *   - 1.0-2.0: 强烈锐化，适合风景或需要突出细节的图像
 *
 * 视觉效果：
 * - 增强图像边缘和细节
 * - 提高图像的视觉清晰度
 * - 不会引入明显的伪影（相比简单锐化）
 * - 保持图像的自然外观
 *
 * 应用场景：
 * - 照片后期处理：提升照片清晰度
 * - 印刷准备：为印刷输出优化图像
 * - 扫描图像处理：改善扫描图像质量
 * - 数字摄影：补偿镜头或传感器的轻微模糊
 * - 图像缩放：减少缩放后的模糊效果
 *
 * @author CH
   * @版本 1.0.0
 * @since 2021/6/11
 */
@SpiDescribe("USM锐化滤镜")
@Spi("usm")
public class ImageUsmFilterImage extends ImageGaussianBlurFilter {

    /**
     * USM 锐化权重，控制锐化强度
     */
    private double weight;

    /**
     * 默认构造函数
     *
     * 使用默认的锐化权重 0.6，适合大多数图像的中等强度锐化。
     */
    public ImageUsmFilterImage() {
        this.weight = 0.6;
    }

    /**
     * 带权重参数的构造函数
     *
     * @param weight 锐化权重，建议范围 0.1-2.0
     *               - 0.1-0.5: 轻微锐化
     *               - 0.5-1.0: 中等锐化
     *               - 1.0-2.0: 强烈锐化
     */
    public ImageUsmFilterImage(double weight) {
        this.weight = weight;
    }

    @Override
    /** 过滤 */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        initial(src);
        int total = width * height;
        byte[] r1 = new byte[total];
        byte[] g1 = new byte[total];
        byte[] b1 = new byte[total];
        System.arraycopy(rArr, 0, r1, 0, total);
        System.arraycopy(gArr, 0, g1, 0, total);
        System.arraycopy(bArr, 0, b1, 0, total);
        byte[][] output = new byte[3][total];
        // 高斯模糊
        super.filter(src, dst);
        int r = 0;
        int g = 0;
        int b = 0;

        int r11 = 0;
        int g11 = 0;
        int b11 = 0;

        int r2 = 0;
        int g2 = 0;
        int b2 = 0;

        for (int i = 0; i < total; i++) {
            r11 = r1[i] & 0xff;
            g11 = g1[i] & 0xff;
            b11 = b1[i] & 0xff;

            r2 = rArr[i] & 0xff;
            g2 = gArr[i] & 0xff;
            b2 = bArr[i] & 0xff;

            r = (int) ((r11 - weight * r2) / (1 - weight));
            g = (int) ((g11 - weight * g2) / (1 - weight));
            b = (int) ((b11 - weight * b2) / (1 - weight));

            output[0][i] = (byte) BufferedImageUtils.clamp(r);
            output[1][i] = (byte) BufferedImageUtils.clamp(g);
            output[2][i] = (byte) BufferedImageUtils.clamp(b);
        }

        putRgb(output[0], output[1], output[2]);
        return toBitmap();
    }


}
