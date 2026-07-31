package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 负片效果图像滤镜
 *
 * 实现经典的负片（反转）效果，通过将每个像素的RGB值进行反转操作，
 * 创建类似胶片负片的视觉效果。
 *
 * 技术原理：
 * - 对每个颜色通道执行反转操作：新值 = 255 - 原值
 * - 保持Alpha通道不变（如果存在）
 * - 亮的区域变暗，暗的区域变亮
 * - 颜色变为其补色
 *
 * 数学公式：
 * - R' = 255 - R
 * - G' = 255 - G
 * - B' = 255 - B
 *
 * 视觉效果：
 * - 白色变为黑色，黑色变为白色
 * - 红色变为青色，绿色变为品红色，蓝色变为黄色
 * - 创建超现实的艺术效果
 * - 突出图像的轮廓和结构
 *
 * 应用场景：
 * - 艺术摄影：创建独特的视觉效果
 * - 图像分析：突出显示图像特征
 * - 创意设计：制作特殊的视觉元素
 * - 医学影像：某些医学图像的显示需求
 * - 夜视效果：模拟夜视设备的显示效果
 *
 * 注意：当前实现创建的是灰度图像，如需保持彩色效果，
 * 应使用TYPE_INT_RGB而不是TYPE_BYTE_GRAY。
 *
 * @author CH
 * @version 1.0.0
 * @since 2021/6/11
 */
@SpiDescribe("负片反转滤镜")
@Spi("negative")
public class ImageNegativeImageFilter extends AbstractImageFilter {

    /**
     * 执行负片滤镜处理
     *
     * 对图像的每个像素进行颜色反转操作，将RGB值转换为其补色。
     *
     * @param src 源图像
     * @param dst 目标图像（此参数未使用）
     * @return 应用负片效果后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        // 注意：这里应该使用TYPE_INT_RGB来保持彩色效果
        BufferedImage negativeImage = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);

        int width = src.getWidth();
        int height = src.getHeight();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixelVal = src.getRGB(x, y);

                // 提取RGB分量
                int red = (pixelVal >> 16) & 0xFF;
                int green = (pixelVal >> 8) & 0xFF;
                int blue = pixelVal & 0xFF;

                // 执行颜色反转：新值 = 255 - 原值
                red = 255 - red;
                green = 255 - green;
                blue = 255 - blue;

                // 重新组合RGB值
                Color negativeColor = new Color(red, green, blue);
                negativeImage.setRGB(x, y, negativeColor.getRGB());
            }
        }

        return negativeImage;
    }
}
