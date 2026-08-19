package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 像素化滤镜
 *
 * 将图像分割为固定大小的像素块，每个块使用其中心点的颜色填充，
 * 产生马赛克或像素化的视觉效果。常用于隐私保护和艺术创作。
 * 通过调整像素步长大小，可以控制像素化的程度。
 *
 * 技术原理：
 * - 将图像按指定步长分割为像素块
 * - 每个像素块取其起始点的RGB颜色值
 * - 用该颜色填充整个像素块区域
 * - 步长越大像素化程度越高
 *
 * 算法流程：
 * 1. 按步长遍历图像的像素点
 * 2. 获取当前像素点的RGB值
 * 3. 使用该颜色填充步长大小的矩形区域
 * 4. 关闭抗锯齿以确保像素块边界清晰
 *
 * 视觉效果：
 * - 复古像素风格，适合像素艺术创作
 * - 马赛克效果，适合隐私保护
 * - 步长越大效果越抽象，步长越小保留越多细节
 * - 关闭抗锯齿确保像素块边界锐利
 *
 * 参数说明：
 * - stepValue: 像素步长（像素），值越小像素化程度越低
 * - 推荐值：5像素，适合大多数图像
 * - 范围：1-20像素，超出范围可能效果不佳
 *
 * 应用场景：
 * - 隐私保护：对敏感区域进行像素化处理
 * - 像素艺术：创建复古像素风格图像
 * - UI设计：像素化风格界面元素
 * - 图像分析：区域简化和特征提取
 * - 缩略图生成：快速生成低分辨率预览
 *
 * 注意事项：
 * - 步长为1时相当于原始图像
 * - 步长过大可能导致图像无法辨识
 * - 处理后图像尺寸与原始图像相同
 * - 建议在处理前检查图像尺寸以获得最佳效果
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Spi("pixel")
@SpiDescribe("像素化滤镜")
public class ImagePixelImageFilter extends AbstractImageFilter{

    /**
     * 默认像素步长，值越小像素化程度越高
     * 推荐值：5像素，适合大多数图像
     */
    private static final int DEFAULT_STEP = 5;

    /**
     * 像素化步长值
     */
    private int stepValue = DEFAULT_STEP;

    /**
     * 默认构造函数
     *
     * 使用默认的像素步长（5像素）创建像素化滤镜。
     */
    public ImagePixelImageFilter() {
        // 使用默认步长
    }

    /**
     * 带参数的构造函数
     *
     * @param stepValue 像素步长，单位为像素，值越小保留细节越多，
     *                  值越大像素化效果越明显。推荐范围1-20
     */
    public ImagePixelImageFilter(int stepValue) {
        this.stepValue = stepValue;
    }


    /**
     * 执行像素化滤镜处理
     *
     * @param src 源图像
     * @param dst 目标图像（可选，若为null则自动创建）
     * @return 像素化处理后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return getPixelImage(src, stepValue);
    
    }

    /**
     * 对图像进行像素化处理
     *
     * 将图像按指定步长分割为像素块，每个块使用其起始点的颜色填充，
     * 产生马赛克效果。算法会关闭抗锯齿以确保像素块边界清晰。
     *
     * @param sourceImage 源图像
     * @param pixelStep 像素步长，值越小保留细节越多，值越大像素化效果越明显
     * @return 像素化处理后的图像
     */
    public BufferedImage getPixelImage(BufferedImage sourceImage, int pixelStep) {
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();
        int minX = sourceImage.getMinX();
        int minY = sourceImage.getMinY();

        // 创建目标图像
        BufferedImage pixelizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = pixelizedImage.createGraphics();

        // 关闭抗锯齿以确保像素块边界清晰
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // 按步长遍历图像的像素点
        for (int x = minX; x < width; x += pixelStep) {
            for (int y = minY; y < height; y += pixelStep) {
                // 获取当前像素点的RGB值
                int pixelRGB = sourceImage.getRGB(x, y);
                int red = (pixelRGB >> 16) & 0xff;
                int green = (pixelRGB >> 8) & 0xff;
                int blue = pixelRGB & 0xff;

                // 设置填充颜色
                graphics.setColor(new Color(red, green, blue));

                // 用当前像素颜色填充步长大小的矩形区域
                // 使用-1作为圆角参数相当于填充矩形
                graphics.fillRoundRect(x, y, pixelStep, pixelStep, -1, -1);
            }
        }

        // 释放图形资源
        graphics.dispose();
        return pixelizedImage;
    }
}