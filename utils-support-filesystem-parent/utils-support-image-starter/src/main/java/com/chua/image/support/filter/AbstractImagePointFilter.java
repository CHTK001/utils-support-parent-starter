package com.chua.image.support.filter;

import com.chua.common.support.constant.NumberConstant;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * 点滤镜抽象基类
 *
 * 提供基于像素点的图像滤镜处理功能。此类专门用于处理每个像素点独立的滤镜效果，
 * 如颜色调整、亮度对比度调整、色彩变换等。接口设计与传统的RGBImageFilter兼容。
 *
 * 主要特点：
 * - 逐像素处理：对每个像素点独立进行滤镜处理
 * - 高性能优化：针对不同图像类型进行优化处理
 * - 内存友好：避免不必要的图像格式转换
 * - 易于扩展：子类只需实现filterRgb方法即可
 *
 * 适用场景：
 * - 颜色调整滤镜（亮度、对比度、饱和度）
 * - 色彩变换滤镜（灰度、负片、复古等）
 * - 阈值处理滤镜（二值化、色彩分离等）
 * - 简单特效滤镜（像素化、马赛克等）
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
public abstract class AbstractImagePointFilter extends AbstractImageFilter {

    /**
     * 是否可以过滤索引颜色模型
     */
    protected boolean canFilterIndexColorModel = false;

    /**
     * 常量：256，颜色值计算
     */
    public static final int MAX_256 = NumberConstant.MAX_256;

    /**
     * 常量：128，颜色值计算
     */
    public static final int MAX_128 = NumberConstant.MAX_128;

    /**
     * 常量：255，颜色值计算
     */
    public static final int MAX_255 = NumberConstant.MAX_255;

    /**
     * 执行点滤镜处理
     *
     * 逐行逐像素地处理图像，对每个像素调用filterRgb方法进行处理。
     * 针对不同的图像类型进行了性能优化，避免不必要的格式转换。
     *
     * @param src 源图像
     * @param dst 目标图像，可以为null
     * @return 处理后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int width = src.getWidth();
        int height = src.getHeight();
        int type = src.getType();
        WritableRaster srcRaster = src.getRaster();

        if (dst == null) {
            dst = createCompatibleDestImage(src, null);
        }
        WritableRaster dstRaster = dst.getRaster();

        setDimensions(width, height);

        int[] inPixels = new int[width];
        for (int y = 0; y < height; y++) {
            // 针对ARGB类型图像进行优化，避免调用getRGB导致的性能问题
            if (type == BufferedImage.TYPE_INT_ARGB) {
                srcRaster.getDataElements(0, y, width, 1, inPixels);
                for (int x = 0; x < width; x++) {
                    inPixels[x] = filterRgb(x, y, inPixels[x]);
                }
                dstRaster.setDataElements(0, y, width, 1, inPixels);
            } else {
                // 对于其他类型的图像，使用标准的getRGB/setRGB方法
                src.getRGB(0, y, width, 1, inPixels, 0, width);
                for (int x = 0; x < width; x++) {
                    inPixels[x] = filterRgb(x, y, inPixels[x]);
                }
                dst.setRGB(0, y, width, 1, inPixels, 0, width);
            }
        }

        return dst;
    }

    /**
     * 抽象的RGB像素滤镜方法
     *
     * 子类必须实现此方法来定义具体的滤镜效果。
     * 此方法对单个像素进行处理，返回处理后的ARGB值。
     *
     * @param x   像素的X坐标
     * @param y   像素的Y坐标
     * @param rgb 原始ARGB像素值
     * @return 处理后的ARGB像素值
     */
    public abstract int filterRgb(int x, int y, int rgb);

    /**
     * 设置图像尺寸
     *
     * 在滤镜处理开始前调用，子类可以重写此方法来进行必要的初始化工作。
     *
     * @param width  图像宽度
     * @param height 图像高度
     */
    public void setDimensions(int width, int height) {
        // 默认实现为空，子类可根据需要重写
    }
}
