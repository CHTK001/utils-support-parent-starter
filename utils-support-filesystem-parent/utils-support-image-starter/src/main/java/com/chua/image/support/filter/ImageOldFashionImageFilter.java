package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 复古怀旧风格图像滤镜
*
* 通过特定的颜色变换矩阵将现代彩色图像转换为具有怀旧复古感的图像效果。
* 该滤镜模拟老式照片的色彩特征，营造温暖、怀念的视觉氛围。
*
* 技术原理：
* - 使用自定义的颜色变换矩阵
* - 调整RGB各通道的权重分配
* - 增强暖色调，减弱冷色调
* - 降低整体饱和度和对比度
*
* 颜色变换矩阵：
* R' = 0.393×R + 0.469×G + 0.049×B
* G' = 0.349×R + 0.586×G + 0.068×B
* B' = 0.272×R + 0.534×G + 0.031×B
*
* 视觉效果特点：
* - 温暖的色调：增强红色和黄色成分
* - 柔和的对比度：降低图像的锐利度
* - 怀旧的氛围：模拟老式胶片的色彩特征
* - 统一的色彩风格：减少色彩的跳跃性
*
* 应用场景：
* - 艺术摄影：创建复古风格的艺术作品
* - 情感表达：营造怀念、温馨的情感氛围
* - 主题设计：复古主题的视觉设计项目
* - 社交媒体：为照片添加流行的复古滤镜效果
* - 品牌营销：营造品牌的历史感和情怀
*
* 算法特点：
* - 线性变换：使用矩阵运算进行颜色转换
* - 保持细节：不会丢失图像的细节信息
* - 计算简单：每个像素独立处理，效率较高
* - 效果稳定：对不同类型的图像都有一致的效果
*
* 注意：当前实现创建的是灰度图像，如需保持彩色复古效果，
* 应使用类型_INT_RGB而不是类型_BYTE_GRAY。
*
* @author CH
* @版本 1.0.0
* @since 2021/6/11
 */
@SpiDescribe("复古怀旧风格滤镜")
@Spi("OldFashion")
public class ImageOldFashionImageFilter extends AbstractImageFilter {

    /**
    * 执行复古滤镜处理
    *
    * 对图像应用复古色彩变换，通过特定的颜色矩阵将现代照片
    * 转换为具有怀旧风格的图像效果。
    *
    * @param src 源图像
    * @param dst 目标图像（此参数未使用）
    * @return 应用复古效果后的图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
 // 注意：这里应该使用类型_INT_RGB来保持彩色复古效果
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

                // 应用复古色彩变换矩阵
                int r = (int) (0.393 * red + 0.469 * green + 0.049 * blue);
                int g = (int) (0.349 * red + 0.586 * green + 0.068 * blue);
                int b = (int) (0.272 * red + 0.534 * green + 0.031 * blue);

                // 确保颜色值在有效范围内
                r = Math.min(255, Math.max(0, r));
                g = Math.min(255, Math.max(0, g));
                b = Math.min(255, Math.max(0, b));

                // 创建复古色彩并设置到图像
                Color vintageColor = new Color(r, g, b);
                vintageImage.setRGB(x, y, vintageColor.getRGB());
            }
        }

        return vintageImage;
    }
}
