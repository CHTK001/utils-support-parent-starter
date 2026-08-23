package com.chua.image.support.filter.lama;

import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.FloatBuffer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * LaMa图像处理工具类
 * <p>
 * 提供图像预处理、后处理和格式转换等功能
 * </p>
 *
 * @author CH
 * @since 2024/7/29
 */
@Slf4j
public class LaMaImageUtils {

    /**
     * 将BufferedImage转换为ONNX输入张量数据
     *
     * @param image  输入图像
     * @param config 配置参数
     * @return 标准化后的图像数据 [C, H, W] 格式
     */
    public static float[] imageToTensor(BufferedImage image, LaMaConfiguration config) {
        int size = config.getInputSize();
        
        // 调整图像尺寸
        BufferedImage resized = resizeImage(image, size, size);
        
        // 转换为RGB格式
        BufferedImage rgbImage = convertToRGB(resized);
        
        // 提取像素数据并标准化
        float[] tensorData = new float[3 * size * size];
        int[] pixels = rgbImage.getRGB(0, 0, size, size, null, 0, size);
        
        float[] means = config.getMeanValues();
        float[] stds = config.getStdValues();
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            
            // 标准化到[0,1]然后应用ImageNet标准化
            float rNorm = (r / 255.0f - means[0]) / stds[0];
            float gNorm = (g / 255.0f - means[1]) / stds[1];
            float bNorm = (b / 255.0f - means[2]) / stds[2];
            
            // CHW格式存储
            // // R通道
            tensorData[i] = rNorm;
            // G通道
            tensorData[size * size + i] = gNorm;
            // B通道
            tensorData[2 * size * size + i] = bNorm;
        }
        
        return tensorData;
    }

    /**
     * 将ONNX输出张量转换为BufferedImage
     *
     * @param tensorData 输出张量数据 [C, H, W] 格式
     * @param config     配置参数
     * @return 转换后的图像
     */
    public static BufferedImage tensorToImage(float[] tensorData, LaMaConfiguration config) {
        int size = config.getInputSize();
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        
        float[] means = config.getMeanValues();
        float[] stds = config.getStdValues();
        
        for (int i = 0; i < size * size; i++) {
            // 从CHW格式读取
            float r = tensorData[i];
            float g = tensorData[size * size + i];
            float b = tensorData[2 * size * size + i];
            
            // 反标准化
            r = (r * stds[0] + means[0]) * 255.0f;
            g = (g * stds[1] + means[1]) * 255.0f;
            b = (b * stds[2] + means[2]) * 255.0f;
            
            // 限制到[0,255]范围
            int rInt = Math.max(0, Math.min(255, Math.round(r)));
            int gInt = Math.max(0, Math.min(255, Math.round(g)));
            int bInt = Math.max(0, Math.min(255, Math.round(b)));
            
            int rgb = (rInt << 16) | (gInt << 8) | bInt;
            
            int x = i % size;
            int y = i / size;
            image.setRGB(x, y, rgb);
        }
        
        return image;
    }

    /**
     * 生成mask张量数据
     *
     * @param image  输入图像
     * @param config 配置参数
     * @return mask数据 [H, W] 格式，1表示需要修复的区域
     */
    public static float[] generateMask(BufferedImage image, LaMaConfiguration config) {
        int size = config.getInputSize();
        
        if (config.isUseAlphaAsMask() && image.getColorModel().hasAlpha()) {
            return generateMaskFromAlpha(image, config);
        } else if (config.isAutoGenerateMask()) {
            return generateMaskFromColor(image, config);
        } else {
            // 默认生成全零mask（不修复任何区域）
            return new float[size * size];
        }
    }

    /**
     * 从alpha通道生成mask
     */
    private static float[] generateMaskFromAlpha(BufferedImage image, LaMaConfiguration config) {
        int size = config.getInputSize();
        BufferedImage resized = resizeImage(image, size, size);
        
        float[] mask = new float[size * size];
        float threshold = config.getMaskThreshold();
        
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int pixel = resized.getRGB(x, y);
                int alpha = (pixel >> 24) & 0xFF;
                float alphaFloat = alpha / 255.0f;
                
                // alpha值小于阈值的区域需要修复
                mask[y * size + x] = alphaFloat < threshold ? 1.0f : 0.0f;
            }
        }
        
        return mask;
    }

    /**
     * 根据颜色生成mask
     */
    private static float[] generateMaskFromColor(BufferedImage image, LaMaConfiguration config) {
        int size = config.getInputSize();
        BufferedImage resized = resizeImage(image, size, size);
        BufferedImage rgbImage = convertToRGB(resized);
        
        float[] mask = new float[size * size];
        int[] targetColor = config.getTargetColor();
        int tolerance = config.getColorTolerance();
        
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int pixel = rgbImage.getRGB(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                
                // 计算颜色距离
                double distance = Math.sqrt(
                    Math.pow(r - targetColor[0], 2) +
                    Math.pow(g - targetColor[1], 2) +
                    Math.pow(b - targetColor[2], 2)
                );
                
                // 距离小于容差的区域需要修复
                mask[y * size + x] = distance <= tolerance ? 1.0f : 0.0f;
            }
        }
        
        return mask;
    }

    /**
     * 调整图像尺寸
     *
     * @param image  原始图像
     * @param width  目标宽度
     * @param height 目标高度
     * @return 调整后的图像
     */
    public static BufferedImage resizeImage(BufferedImage image, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        
        // 设置高质量渲染
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        g2d.drawImage(image, 0, 0, width, height, null);
        g2d.dispose();
        
        return resized;
    }

    /**
     * 转换为RGB格式
     *
     * @param image 输入图像
     * @return RGB格式图像
     */
    public static BufferedImage convertToRGB(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        
        BufferedImage rgbImage = new BufferedImage(
            image.getWidth(), 
            image.getHeight(), 
            BufferedImage.TYPE_INT_RGB
        );
        
        Graphics2D g2d = rgbImage.createGraphics();
        g2d.drawImage(image, 0, 0, null);
        g2d.dispose();
        
        return rgbImage;
    }

    /**
     * 应用边缘羽化效果
     *
     * @param original 原始图像
     * @param inpainted 修复后的图像
     * @param mask     修复mask
     * @param radius   羽化半径
     * @return 羽化后的图像
     */
    public static BufferedImage applyFeathering(BufferedImage original, BufferedImage inpainted, 
                                               float[] mask, int radius) {
        if (radius <= 0) {
            return inpainted;
        }
        
        int width = original.getWidth();
        int height = original.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        
        // 创建羽化后的mask
        float[] featheredMask = createFeatheredMask(mask, width, height, radius);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                float alpha = featheredMask[idx];
                
                int originalRGB = original.getRGB(x, y);
                int inpaintedRGB = inpainted.getRGB(x, y);
                
                // 混合原始图像和修复图像
                int blendedRGB = blendPixels(originalRGB, inpaintedRGB, alpha);
                result.setRGB(x, y, blendedRGB);
            }
        }
        
        return result;
    }

    /**
     * 创建羽化mask
     */
    private static float[] createFeatheredMask(float[] mask, int width, int height, int radius) {
        float[] feathered = mask.clone();
        
        // 简单的高斯模糊近似
        for (int iter = 0; iter < radius; iter++) {
            float[] temp = new float[width * height];
            
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float sum = 0;
                    int count = 0;
                    
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            
                            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                                sum += feathered[ny * width + nx];
                                count++;
                            }
                        }
                    }
                    
                    temp[y * width + x] = sum / count;
                }
            }
            
            feathered = temp;
        }
        
        return feathered;
    }

    /**
     * 混合两个像素
     */
    private static int blendPixels(int pixel1, int pixel2, float alpha) {
        int r1 = (pixel1 >> 16) & 0xFF;
        int g1 = (pixel1 >> 8) & 0xFF;
        int b1 = pixel1 & 0xFF;
        
        int r2 = (pixel2 >> 16) & 0xFF;
        int g2 = (pixel2 >> 8) & 0xFF;
        int b2 = pixel2 & 0xFF;
        
        int r = Math.round(r1 * (1 - alpha) + r2 * alpha);
        int g = Math.round(g1 * (1 - alpha) + g2 * alpha);
        int b = Math.round(b1 * (1 - alpha) + b2 * alpha);
        
        return (r << 16) | (g << 8) | b;
    }

    /**
     * 应用后处理优化
     *
     * @param image  输入图像
     * @param config 配置参数
     * @return 优化后的图像
     */
    public static BufferedImage applyPostProcessing(BufferedImage image, LaMaConfiguration config) {
        if (!config.isEnablePostProcessing()) {
            return image;
        }
        
        // 应用轻微的锐化
        BufferedImage sharpened = applySharpen(image);
        
        // 应用颜色校正
        BufferedImage corrected = applyColorCorrection(sharpened);
        
        return corrected;
    }

    /**
     * 应用锐化滤镜
     */
    private static BufferedImage applySharpen(BufferedImage image) {
        // 简单的锐化核
        float[] sharpenKernel = {
            0, -1, 0,
            -1, 5, -1,
            0, -1, 0
        };
        
        return applyConvolution(image, sharpenKernel, 3);
    }

    /**
     * 应用颜色校正
     */
    private static BufferedImage applyColorCorrection(BufferedImage image) {
        // 简单的对比度和亮度调整
        BufferedImage corrected = new BufferedImage(
            image.getWidth(), 
            image.getHeight(), 
            BufferedImage.TYPE_INT_RGB
        );
        
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                
                // 轻微增强对比度
                r = Math.max(0, Math.min(255, (int)(r * 1.05 - 2)));
                g = Math.max(0, Math.min(255, (int)(g * 1.05 - 2)));
                b = Math.max(0, Math.min(255, (int)(b * 1.05 - 2)));
                
                corrected.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        
        return corrected;
    }

    /**
     * 应用卷积操作
     */
    private static BufferedImage applyConvolution(BufferedImage image, float[] kernel, int kernelSize) {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        
        int offset = kernelSize / 2;
        
        for (int y = offset; y < height - offset; y++) {
            for (int x = offset; x < width - offset; x++) {
                float r = 0, g = 0, b = 0;
                
                for (int ky = 0; ky < kernelSize; ky++) {
                    for (int kx = 0; kx < kernelSize; kx++) {
                        int px = x + kx - offset;
                        int py = y + ky - offset;
                        
                        int rgb = image.getRGB(px, py);
                        float weight = kernel[ky * kernelSize + kx];
                        
                        r += ((rgb >> 16) & 0xFF) * weight;
                        g += ((rgb >> 8) & 0xFF) * weight;
                        b += (rgb & 0xFF) * weight;
                    }
                }
                
                int rInt = Math.max(0, Math.min(255, Math.round(r)));
                int gInt = Math.max(0, Math.min(255, Math.round(g)));
                int bInt = Math.max(0, Math.min(255, Math.round(b)));
                
                result.setRGB(x, y, (rInt << 16) | (gInt << 8) | bInt);
            }
        }
        
        return result;
    }
}
