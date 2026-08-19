package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 复古旧滤镜
 *
 * 将图像转换为复古怀旧风格，模拟老照片的色调效果。常用于摄影后期和艺术创作。
 * 通过调整RGB通道权重，使图像呈现暖色调的怀旧效果。
 *
 * 技术原理：
 * - 使用加权矩阵对RGB通道进行线性变换
 * - 增强红色通道，减少蓝色通道
 * - 保持绿色通道基本不变
 * - 结果值限制在0-255范围内
 *
 * 变换公式：
 * R' = 0.393*R + 0.469*G + 0.049*B
 * G' = 0.349*R + 0.586*G + 0.068*B
 * B' = 0.272*R + 0.534*G + 0.031*B
 *
 * 视觉效果：
 * - 暖色调偏移，模拟老照片褪色效果
 * - 红棕色主导的整体色调
 * - 适合人像和风景的怀旧处理
 * - 可与其他滤镜叠加使用
 *
 * 应用场景：
 * - 照片后期：快速添加怀旧色调
 * - 艺术创作：复古风格图像处理
 * - 社交媒体：打造怀旧氛围
 * - UI设计：复古界面风格元素
 * - 视频处理：逐帧应用实现复古视频
 *
 * 注意事项：
 * - 输入图像应为TYPE_INT_RGB格式以确保最佳效果
 * - 对于灰度图像会自动转换为RGB处理
 * - 每个通道值会自动限制在0-255范围内
 * - 处理大图像时可能需要考虑性能优化
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@SpiDescribe("复古旧滤镜")
@Spi("OldFashion")
public class ImageOldFashionImageFilter extends AbstractImageFilter {

    /**
     * 执行复古滤镜处理
     *
     * 使用加权矩阵对图像的每个像素进行RGB通道变换，
     * 生成具有暖色调的复古风格图像。
     * 结果值自动限制在0-255范围内。
     *
     * @param src 源图像
     * @param dst 目标图像（可选，若为null则自动创建）
     * @return 处理后的复古风格图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        // 创建TYPE_INT_RGB格式的目标图像以确保兼容性
        BufferedImage vintageImage = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);

        int width = src.getWidth();
        int height = src.getHeight();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int pixelVal = src.getRGB(x, y);

                // 提取RGB分量
                int red = (pixelVal >> 16) & 0xFF;
                int green = (pixelVal >> 8) & 0xFF;
                int blue = pixelVal & 0xFF;

                // 应用复古变换矩阵
                int r = (int) (0.393 * red + 0.469 * green + 0.049 * blue);
                int g = (int) (0.349 * red + 0.586 * green + 0.068 * blue);
                int b = (int) (0.272 * red + 0.534 * green + 0.031 * blue);

                // 限制通道值在0-255范围内
                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                // 设置变换后的复古色调像素
                Color vintageColor = new Color(r, g, b);
                vintageImage.setRGB(x, y, vintageColor.getRGB());
            }
        }

        return vintageImage;
    }
}