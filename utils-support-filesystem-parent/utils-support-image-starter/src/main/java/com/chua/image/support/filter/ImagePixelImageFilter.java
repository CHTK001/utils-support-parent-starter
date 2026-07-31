package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 像素化图像滤镜
 *
 * 将图像转换为像素艺术风格，通过降低图像分辨率并使用方形像素块
 * 来重现图像内容，创建复古的8位游戏风格视觉效果。
 *
 * 技术原理：
 * - 网格采样：按指定步长对图像进行网格采样
 * - 像素块填充：用采样点的颜色填充整个像素块
 * - 分辨率降低：通过增大像素块尺寸降低图像分辨率
 * - 颜色简化：减少图像中的颜色细节
 *
 * 算法流程：
 * 1. 按指定步长遍历图像像素
 * 2. 获取采样点的RGB颜色值
 * 3. 用该颜色填充对应的矩形像素块
 * 4. 重复直到覆盖整个图像
 *
 * 视觉效果特点：
 * - 复古风格：模拟早期电子游戏的像素艺术
 * - 简化细节：减少图像的细节复杂度
 * - 方块效果：明显的方形像素块结构
 * - 色彩保持：保留原图像的主要色彩信息
 *
 * 参数控制：
 * - 步长值越小：像素化程度越高，效果越明显
 * - 步长值越大：保留更多细节，效果越轻微
 * - 建议范围：3-20像素，根据图像大小调整
 *
 * 应用场景：
 * - 游戏开发：创建复古像素游戏的艺术风格
 * - 艺术创作：制作像素艺术作品
 * - 网页设计：创建独特的视觉元素
 * - 社交媒体：为照片添加复古滤镜效果
 * - 品牌设计：营造怀旧、复古的品牌形象
 *
 * 性能特点：
 * - 高效处理：通过跳跃采样减少计算量
 * - 内存友好：生成的图像保持原始尺寸
 * - 可调节性：通过步长参数控制效果强度
 * - 实时性：适合实时图像处理应用
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/5/27
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
     * 当前使用的像素步长
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
     * @param stepValue 像素步长，控制像素化程度值越小效果越明显，建议范围3-20
     */
    public ImagePixelImageFilter(int stepValue) {
        this.stepValue = stepValue;
    }


    /**
     * 执行像素化滤镜处理
     *
     * @param src 源图像
     * @param dst 目标图像（此参数未使用）
     * @return 像素化处理后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return getPixelImage(src, stepValue);
    
    }

    /**
     * 生成像素化图像
     *
     * 通过网格采样和像素块填充的方式将普通图像转换为像素艺术风格。
     * 算法会按指定步长遍历图像，用采样点的颜色填充对应的矩形区域。
     *
     * @param sourceImage 输入的源图像
     * @param pixelStep 像素步长，控制像素化程度值越小像素化效果越明显
     * @return 像素化处理后的图像
     */
    public BufferedImage getPixelImage(BufferedImage sourceImage, int pixelStep) {
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();
        int minX = sourceImage.getMinX();
        int minY = sourceImage.getMinY();

        // 创建输出图像
        BufferedImage pixelizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = pixelizedImage.createGraphics();

        // 设置渲染质量（可选）
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // 按步长遍历图像，创建像素块
        for (int x = minX; x < width; x += pixelStep) {
            for (int y = minY; y < height; y += pixelStep) {
                // 获取采样点的颜色
                int pixelRGB = sourceImage.getRGB(x, y);
                int red = (pixelRGB >> 16) & 0xff;
                int green = (pixelRGB >> 8) & 0xff;
                int blue = pixelRGB & 0xff;

                // 设置画笔颜色
                graphics.setColor(new Color(red, green, blue));

                // 填充像素块（使用圆角矩形创建更柔和的像素效果）
                // 参数-1表示圆角半径为0，即普通矩形
                graphics.fillRoundRect(x, y, pixelStep, pixelStep, -1, -1);
            }
        }

        // 释放图形资源
        graphics.dispose();
        return pixelizedImage;
    }
}

