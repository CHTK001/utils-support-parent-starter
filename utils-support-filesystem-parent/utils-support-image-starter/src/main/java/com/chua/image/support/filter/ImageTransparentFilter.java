package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
* @author CH
* @since 4.0.0.42
 */
/**
* 透明度背景移除滤镜
*
* 检测图像中的透明区域并将其设置为完全透明，实现背景移除效果。
* 主要用于处理带有Alpha通道的PNG图像，将纯黑或指定颜色的背景设为透明。
* 适用于图标、Logo等需要透明背景的图像处理场景。
*
* 技术原理：
* - 检测图像中每个像素的RGB值
* - 将符合条件（如纯黑）的像素设为完全透明
* - 利用Alpha通道实现透明效果
* - 保留非背景区域的原始颜色
*
* 算法流程：
* 1. 遍历图像的每个像素
* 2. 获取当前像素的RGB值
* 3. 检查是否为背景色（如纯黑：R=0,G=0,B=0）
* 4. 将背景像素的Alpha设为0（完全透明）
* 5. 将处理后的像素写入新的缓冲镜像
*
* 默认行为：
* - 将纯黑像素（R=0,G=0,B=0）设为透明
* - 保留所有非纯黑像素的原始颜色和透明度
* - 输出类型_INT_ARGB格式以支持Alpha通道
*
* 应用场景：
* - 图标处理：移除图标背景使其透明
* - Logo处理：去除Logo背景用于叠加
* - 证件照处理：简化背景替换流程
* - 电商图片：商品图背景移除
* - UI设计：制作透明背景素材
*
* 注意事项：
* - 仅对纯黑(R=0,G=0,B=0)像素生效
* - 输出图像为类型_INT_ARGB格式以保留透明度
* - 近似黑色的像素不会被处理，需预处理调整阈值
* - 对于复杂背景可能需要更高级的背景分割算法
*
* @author CH
* @版本 1.0.0
* @since 4.0.0.42
 */
@Spi("transparent")
@SpiDescribe("透明度背景移除滤镜")
public class ImageTransparentFilter extends AbstractImageFilter{
    /**
    * 对图像应用透明度过滤。
    * 此方法旨在被子类覆盖，以实现具体的透明度过滤逻辑。
    * 当前实现返回 空，表示尚未实现具体的过滤逻辑。
    *
    * @param src 源图像，包含需要处理的像素数据
    * @param dst 目标图像，用于存储过滤后的结果，可以为空
    * @return 处理后的透明度过滤图像，当前实现返回null
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        BufferedImage newImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        // 遍历图像像素
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // 获取当前像素的RGB值
                int rgba = src.getRGB(x, y);

                // 创建Color对象并解析RGB分量
                Color color = new Color(rgba, true);

                // 检查是否为纯黑背景像素，将纯黑设为完全透明
                if (color.getRed() == 0 && color.getGreen() == 0 && color.getBlue() == 0) {
                    color = new Color(0, 0, 0, 0);
                }

 // 将处理后的像素写入新的缓冲镜像
                newImage.setRGB(x, y, color.getRGB());
            }
        }
        return newImage;
    }
}
