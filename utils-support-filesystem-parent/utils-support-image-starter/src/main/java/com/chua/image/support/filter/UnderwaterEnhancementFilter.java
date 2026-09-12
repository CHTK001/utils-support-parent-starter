package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 水下增强滤镜
 * <p>
 * 专门用于增强水下图像的滤镜，主要解决水下图像的以下问题：
 * 1. 蓝绿色偏色问题
 * 2. 对比度低
 * 3. 细节模糊
 * 4. 颜色饱和度不足
 * 
 * 算法特点：
 * - 白平衡校正：消除蓝绿色调偏移
 * - 对比度增强：提升图像层次感
 * - 颜色补偿：恢复红色通道信息
 * - 细节锐化：增强图像清晰度
 * - 饱和度调整：提升颜色鲜艳度
 *
 * @author CH
 * @since 2024/12/20
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("underwater")
@SpiDescribe("水下增强滤镜")
@Accessors(chain = true)
public class UnderwaterEnhancementFilter extends AbstractImageFilter {

    /**
     * 红色通道增强系数 (0.0-2.0)
     * 用于补偿水下环境中红光衰减的问题
     */
    private double redEnhancement = 1.5;

    /**
     * 蓝色通道衰减系数 (0.0-1.0)
     * 用于减少水下图像的蓝色偏移
     */
    private double blueReduction = 0.7;

    /**
     * 绿色通道衰减系数 (0.0-1.0)
     * 用于减少水下图像的绿色偏移
     */
    private double greenReduction = 0.8;

    /**
     * 对比度增强系数 (0.0-3.0)
     * 用于提升图像的对比度
     */
    private double contrastEnhancement = 1.3;

    /**
     * 饱和度增强系数 (0.0-2.0)
     * 用于提升颜色饱和度
     */
    private double saturationEnhancement = 1.2;

    /**
     * 亮度调整系数 (-100 到 100)
     * 用于调整图像整体亮度
     */
    private int brightnessAdjustment = 10;

    /**
     * 是否启用锐化处理
     */
    private boolean sharpenEnabled = true;

    /**
     * 锐化强度 (0.0-2.0)
     */
    private double sharpenStrength = 0.5;

    @Override
    /** 获取镜像格式化 */
    public String getImageFormat() {
        
        return "jpeg";
    
    }

    @Override
    /** 获取镜像格式化 */
    public String getImageFormat(String name) {
        if (name == null) {
            return getImageFormat();
        }
        String lowerName = name.toLowerCase();
        if (lowerName.endsWith(".png")) {
            return "png";
        } else if (lowerName.endsWith(".gif")) {
            return "gif";
        } else if (lowerName.endsWith(".bmp")) {
            return "bmp";
        }
        return "jpeg";
    }

    @Override
    /** 过滤 */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int width = src.getWidth();
        int height = src.getHeight();

        if (dst == null) {
            dst = new BufferedImage(width, height, BufferedImageUtils.safeType(src));
        }

        // 第一步：颜色校正和增强
        BufferedImage colorCorrected = performColorCorrection(src);
        
        // 第二步：对比度和亮度调整
        BufferedImage contrastAdjusted = adjustContrastAndBrightness(colorCorrected);
        
        // 第三步：饱和度增强
        BufferedImage saturationEnhanced = enhanceSaturation(contrastAdjusted);
        
        // 第四步：锐化处理（可选）
        BufferedImage finalImage = saturationEnhanced;
        if (sharpenEnabled) {
            finalImage = applySharpen(saturationEnhanced);
        }

        // 复制最终结果到目标图像
        dst.getGraphics().drawImage(finalImage, 0, 0, null);
        return dst;
    }

    /**
     * 执行颜色校正
     * 主要解决水下图像的蓝绿色偏移问题
     * @param src src
     * @return 执行colorcorrection的结果
     */
    private BufferedImage performColorCorrection(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImageUtils.safeType(src));

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                
                // 提取RGB分量
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 应用颜色校正
                red = ImageProcessorUtils.clamp((int) (red * redEnhancement));
                green = ImageProcessorUtils.clamp((int) (green * greenReduction));
                blue = ImageProcessorUtils.clamp((int) (blue * blueReduction));

                // 重新组合RGB
                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 调整对比度和亮度
     * @param src src
     * @return adjustcontrast和brightness的结果
     */
    private BufferedImage adjustContrastAndBrightness(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImageUtils.safeType(src));

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 应用对比度增强
                red = ImageProcessorUtils.clamp((int) ((red - 128) * contrastEnhancement + 128 + brightnessAdjustment));
                green = ImageProcessorUtils.clamp((int) ((green - 128) * contrastEnhancement + 128 + brightnessAdjustment));
                blue = ImageProcessorUtils.clamp((int) ((blue - 128) * contrastEnhancement + 128 + brightnessAdjustment));

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 增强饱和度
     * @param src src
     * @return 增强saturation的结果
     */
    private BufferedImage enhanceSaturation(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImageUtils.safeType(src));

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 转换到HSV色彩空间进行饱和度调整
                float[] hsv = rgbToHsv(red, green, blue);
// 转换到HSV色彩空间进行饱和度调整
hsv[1] = Math.min(1.0f, (float) (hsv[1] * saturationEnhancement));

                // 转换回RGB
                int[] newRgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);
                
                int finalRgb = (alpha << 24) | (newRgb[0] << 16) | (newRgb[1] << 8) | newRgb[2];
                result.setRGB(x, y, finalRgb);
            }
        }

        return result;
    }

    /**
     * 应用锐化滤镜
     * @param src src
     * @return applySharpen的结果
     */
    private BufferedImage applySharpen(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImageUtils.safeType(src));

        // 锐化卷积核
        double[][] kernel = {
            {0, -sharpenStrength, 0},
            {-sharpenStrength, 1 + 4 * sharpenStrength, -sharpenStrength},
            {0, -sharpenStrength, 0}
        };

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                double redSum = 0, greenSum = 0, blueSum = 0;
                int alpha = (src.getRGB(x, y) >> 24) & 0xFF;

                // 应用卷积核
                for (int ky = -1; ky <= 1; ky++) {
                    for (int kx = -1; kx <= 1; kx++) {
                        int rgb = src.getRGB(x + kx, y + ky);
                        int red = (rgb >> 16) & 0xFF;
                        int green = (rgb >> 8) & 0xFF;
                        int blue = rgb & 0xFF;

                        double weight = kernel[ky + 1][kx + 1];
                        redSum += red * weight;
                        greenSum += green * weight;
                        blueSum += blue * weight;
                    }
                }

                int newRed = ImageProcessorUtils.clamp((int) redSum);
                int newGreen = ImageProcessorUtils.clamp((int) greenSum);
                int newBlue = ImageProcessorUtils.clamp((int) blueSum);

                int newRgb = (alpha << 24) | (newRed << 16) | (newGreen << 8) | newBlue;
                result.setRGB(x, y, newRgb);
            }
        }

        // 处理边缘像素（直接复制）
        for (int x = 0; x < width; x++) {
            result.setRGB(x, 0, src.getRGB(x, 0));
            result.setRGB(x, height - 1, src.getRGB(x, height - 1));
        }
        for (int y = 0; y < height; y++) {
            result.setRGB(0, y, src.getRGB(0, y));
            result.setRGB(width - 1, y, src.getRGB(width - 1, y));
        }

        return result;
    }

    @Override
    /** 转换器 */
    public OutputStream converter(InputStream image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        // 读取输入流到字节数组
        byte[] buffer = new byte[1024];
        int bytesRead;
        ByteArrayOutputStream tempOutput = new ByteArrayOutputStream();
        while ((bytesRead = image.read(buffer)) != -1) {
            tempOutput.write(buffer, 0, bytesRead);
        }
        
 // 转换为缓冲镜像并应用滤镜
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(tempOutput.toByteArray())) {
            BufferedImage bufferedImage = javax.imageio.ImageIO.read(inputStream);
            if (bufferedImage != null) {
                BufferedImage filteredImage = converter(bufferedImage);
                javax.imageio.ImageIO.write(filteredImage, getImageFormat(), outputStream);
            }
        }
        
        return outputStream;
    }

    /**
     * RGB转HSV色彩空间
     * @param r r
     * @param g g
     * @param b b
     * @return rgb转为hsv的结果
     */
    private float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255.0f;
        float gf = g / 255.0f;
        float bf = b / 255.0f;

        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;

        float h = 0, s = 0, v = max;

        if (delta != 0) {
            s = delta / max;
            
            if (max == rf) {
                h = ((gf - bf) / delta) % 6;
            } else if (max == gf) {
                h = (bf - rf) / delta + 2;
            } else {
                h = (rf - gf) / delta + 4;
            }
            h *= 60;
            if (h < 0) {
                h += 360;
            }
        }

        return new float[]{h / 360.0f, s, v};
    }

    /**
     * HSV转RGB色彩空间
     * @param h h
     * @param s s
     * @param v v
     * @return hsv转为rgb的结果
     */
    private int[] hsvToRgb(float h, float s, float v) {
        h *= 360;
        int c = (int) (v * s * 255);
        int x = (int) (c * (1 - Math.abs((h / 60) % 2 - 1)));
        int m = (int) (v * 255) - c;

        int r = 0, g = 0, b = 0;

        if (h >= 0 && h < 60) {
            r = c; g = x; b = 0;
        } else if (h >= 60 && h < 120) {
            r = x; g = c; b = 0;
        } else if (h >= 120 && h < 180) {
            r = 0; g = c; b = x;
        } else if (h >= 180 && h < 240) {
            r = 0; g = x; b = c;
        } else if (h >= 240 && h < 300) {
            r = x; g = 0; b = c;
        } else if (h >= 300 && h < 360) {
            r = c; g = 0; b = x;
        }

        return new int[]{
            ImageProcessorUtils.clamp(r + m),
            ImageProcessorUtils.clamp(g + m),
            ImageProcessorUtils.clamp(b + m)
        };
    }
}

