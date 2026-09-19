package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 夜视效果图像滤镜
 *
 * 模拟夜视设备的视觉效果，通过调整图像的饱和度和亮度来创建
 * 类似夜视镜或红外成像设备的图像效果。
 *
 * 技术原理：
 * - 增强饱和度：将RGB各通道乘以1.2倍
 * - 降低亮度：从每个通道减去50个亮度单位
 * - 模拟低光环境：创建暗淡但可见的图像效果
 * - 保持Alpha通道：维持图像的透明度信息
 *
 * 算法流程：
 * 1. 提取原始像素的RGB分量
 * 2. 对每个颜色通道应用增强公式：新值 = (原值 × 1.2) - 50
 * 3. 限制颜色值在0-255的有效范围内
 * 4. 重新组合ARGB像素值
 *
 * 视觉效果特点：
 * - 整体偏暗：模拟低光照环境
 * - 对比度增强：提高图像的可见度
 * - 色彩饱和：增强颜色的鲜艳度
 * - 神秘感：营造夜间或特殊环境的氛围
 *
 * 应用场景：
 * - 游戏开发：夜间场景或特殊视觉效果
 * - 影视后期：模拟夜视镜头的拍摄效果
 * - 安防监控：增强低光照条件下的图像可见度
 * - 艺术创作：创建神秘、科幻的视觉风格
 * - 主题摄影：军事、探险主题的图像处理
 *
 * 算法特点：
 * - 简单高效：线性变换，计算复杂度低
 * - 实时处理：适合实时图像处理应用
 * - 参数固定：使用预设的增强参数
 * - 边界安全：自动处理颜色值溢出
 *
 * 改进建议：
 * - 可以添加绿色滤镜效果，更接近真实夜视设备
 * - 可以调整增强参数，适应不同的图像特点
 * - 可以添加噪声效果，模拟设备的电子噪声
 *
 * @author CH
 * @版本 1.0.0
 * @since 2024/10/2
 */
@Spi("nightVision")
@SpiDescribe("夜视效果滤镜")
public class NightVisionImageFilter extends AbstractImageFilter{

    /**
     * 饱和度增强因子
     */
    private static final double SATURATION_FACTOR = 1.2;

    /**
     * 亮度减少量
     */
    private static final int BRIGHTNESS_REDUCTION = 50;

    /**
     * 执行夜视效果滤镜处理
     *
     * 对图像应用夜视效果，通过增强饱和度和降低亮度来模拟
     * 夜视设备的视觉效果。
     *
     * @param src 源图像
     * @param dst 目标图像（此参数未使用）
     * @return 应用夜视效果后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        BufferedImage nightVisionImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int rgb = src.getRGB(x, y);
                // 设置完全不透明
int alpha = (rgb >> 24) & 0xff;
                int red, green, blue;

                // 增强饱和度并降低亮度以模拟夜视效果
                red = (int) ((((rgb >> 16) & 0xff) * SATURATION_FACTOR) - BRIGHTNESS_REDUCTION);
                green = (int) ((((rgb >> 8) & 0xff) * SATURATION_FACTOR) - BRIGHTNESS_REDUCTION);
                blue = (int) (((rgb & 0xff) * SATURATION_FACTOR) - BRIGHTNESS_REDUCTION);

                // 处理颜色值边界，确保在0-255范围内
                red = Math.max(Math.min(red, 0xff), 0);
                green = Math.max(Math.min(green, 0xff), 0);
                blue = Math.max(Math.min(blue, 0xff), 0);

                // 重新组合ARGB像素值
                rgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                nightVisionImage.setRGB(x, y, rgb);
            }
        }

        return nightVisionImage;
    }
}
