package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * BSC 图像调整滤镜
 *
 * BSC (Brightness, Saturation, Contrast) 滤镜提供对图像亮度、饱和度和对比度的
 * 综合调整功能。通过 HSL 颜色空间转换实现精确的颜色调整。
 *
 * 技术原理：
 * - RGB 到 HSL 颜色空间转换
 * - 在 HSL 空间中调整亮度和饱和度
 * - 在 RGB 空间中调整对比度
 * - HSL 到 RGB 颜色空间逆转换
 *
 * 调整参数：
 * - 亮度 (Brightness)：控制图像的明暗程度
 * - 饱和度 (Saturation)：控制颜色的鲜艳程度
 * - 对比度 (Contrast)：控制明暗对比的强度
 *
 * 参数范围：
 * - 所有参数以百分比形式输入（-100 到 +100）
 * - 0 表示不调整，正值增强，负值减弱
 * - 内部自动转换为乘法因子（0.0 到 2.0）
 *
 * 应用场景：
 * - 照片后期处理：调整照片的色彩和明暗
 * - 图像增强：改善图像的视觉效果
 * - 色彩校正：修正图像的色彩偏差
 * - 艺术效果：创建特定的视觉风格
 * - 显示适配：为不同显示设备优化图像
 *
 * @author CH
 * @version 1.0.0
 * @since 2021/6/11
 */
@Spi("bsc")
@SpiDescribe("亮度饱和度对比度调整滤镜")
public class BscAdjustImageFilter extends AbstractImageFilter {

    /**
     * 亮度调整值，范围 -100 到 +100
     */
    private double brightness;

    /**
     * 对比度调整值，范围 -100 到 +100
     */
    private double contrast;

    /**
     * 饱和度调整值，范围 -100 到 +100
     */
    private double saturation;

    /**
     * 获取亮度调整值
     *
     * @return 亮度调整值，范围 -100 到 +100
     */
    public double getBrightness() {
        return brightness;
    }

    /**
     * 设置亮度调整值
     *
     * @param brightness 亮度调整值，范围 -100 到 +100，0表示不调整
     */
    public void setBrightness(double brightness) {
        this.brightness = brightness;
    }

    /**
     * 获取饱和度调整值
     *
     * @return 饱和度调整值，范围 -100 到 +100
     */
    public double getSaturation() {
        return saturation;
    }

    /**
     * 设置饱和度调整值
     *
     * @param saturation 饱和度调整值，范围 -100 到 +100，0表示不调整
     */
    public void setSaturation(double saturation) {
        this.saturation = saturation;
    }

    /**
     * 获取对比度调整值
     *
     * @return 对比度调整值，范围 -100 到 +100
     */
    public double getContrast() {
        return contrast;
    }

    /**
     * 设置对比度调整值
     *
     * @param contrast 对比度调整值，范围 -100 到 +100，0表示不调整
     */
    public void setContrast(double contrast) {
        this.contrast = contrast;
    }

    /**
     * 执行 BSC 调整滤镜处理
     *
     * 对图像进行亮度、饱和度和对比度的综合调整。
     * 使用 HSL 颜色空间进行亮度和饱和度调整，RGB 空间进行对比度调整。
     *
     * @param src  源图像
     * @param dest 目标图像，可以为 null
     * @return 调整后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dest) {
        // 处理参数，将百分比转换为乘法因子
        handleParameters();

        int width = src.getWidth();
        int height = src.getHeight();

        if (dest == null) {
            dest = creatCompatibleDestImage(src, null);
        }

        int[] inPixels = new int[width * height];
        int[] outPixels = new int[width * height];
        getRgb(src, 0, 0, width, height, inPixels);

        int index = 0;
        for (int row = 0; row < height; row++) {
            int ta = 0, tr = 0, tg = 0, tb = 0;
            for (int col = 0; col < width; col++) {
                index = row * width + col;

// 提取 ARGB 分量
        // Alpha 通道
        ta = (inPixels[index] >> 24) & 0xff;
        // 红色通道
        tr = (inPixels[index] >> 16) & 0xff;
        // 绿色通道
        tg = (inPixels[index] >> 8) & 0xff;
        // 蓝色通道
        tb = inPixels[index] & 0xff;

                // RGB 转换为 HSL 色彩空间
                double[] hsl = rgb2Hsl(new int[]{tr, tg, tb});

                // 调整饱和度（在 HSL 空间中）
                hsl[1] = hsl[1] * saturation;
                if (hsl[1] < 0.0) {
                    hsl[1] = 0.0;
                }
                if (hsl[1] > 255.0) {
                    hsl[1] = 255.0;
                }

                // 调整亮度（在 HSL 空间中）
                hsl[2] = hsl[2] * brightness;
                if (hsl[2] < 0.0) {
                    hsl[2] = 0.0;
                }
                if (hsl[2] > 255.0) {
                    hsl[2] = 255.0;
                }

                // HSL 转换回 RGB 空间
                int[] rgb = hsl2Rgb(hsl);
                tr = ImageProcessorUtils.clamp(rgb[0]);
                tg = ImageProcessorUtils.clamp(rgb[1]);
                tb = ImageProcessorUtils.clamp(rgb[2]);

                // 调整对比度（在 RGB 空间中）
                double cr = ((tr / 255.0d) - 0.5d) * contrast;
                double cg = ((tg / 255.0d) - 0.5d) * contrast;
                double cb = ((tb / 255.0d) - 0.5d) * contrast;

                // 计算最终的 RGB 值
                tr = (int) ((cr + 0.5f) * 255.0f);
                tg = (int) ((cg + 0.5f) * 255.0f);
                tb = (int) ((cb + 0.5f) * 255.0f);

                // 重新组合 ARGB 值
                outPixels[index] = (ta << 24) | (ImageProcessorUtils.clamp(tr) << 16) | (ImageProcessorUtils.clamp(tg) << 8) | ImageProcessorUtils.clamp(tb);
            }
        }

        setRgb(dest, 0, 0, width, height, outPixels);
        return dest;
    }

    /**
     * 处理调整参数
     *
     * 将百分比形式的调整参数转换为乘法因子。
     * 输入范围 -100 到 +100，转换为 0.0 到 2.0 的乘法因子。
     */
    public void handleParameters() {
        contrast = (1.0 + contrast / 100.0);
        brightness = (1.0 + brightness / 100.0);
        saturation = (1.0 + saturation / 100.0);
    }

}
