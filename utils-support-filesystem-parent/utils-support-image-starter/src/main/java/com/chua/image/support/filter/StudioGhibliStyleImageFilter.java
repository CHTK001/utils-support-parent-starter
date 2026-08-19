package com.chua.image.support.filter;

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
 * 宫崎骏风格滤镜
 * <p>
 * 模拟宫崎骏动画电影的画风特点，创造温暖、梦幻的视觉效果：
 * 1. 温暖的色调调整
 * 2. 柔和的光影效果
 * 3. 增强的饱和度
 * 4. 梦幻的氛围感
 * 5. 细腻的色彩过渡
 * 
 * 算法特点：
 * - 暖色调增强：增加橙色和黄色的温暖感
 * - 柔光效果：模拟动画中的柔和光线
 * - 色彩饱和度提升：让颜色更加鲜艳生动
 * - 对比度优化：保持细节的同时营造氛围
 * - 边缘柔化：减少锐利边缘，增加手绘感
 *
 * @author CH
 * @since 2024/12/20
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("ghibli")
@SpiDescribe("宫崎骏风格滤镜")
@Accessors(chain = true)
public class StudioGhibliStyleImageFilter extends AbstractImageFilter {

    /**
     * 暖色调增强系数 (0.0-2.0)
     * 增加橙色和黄色的温暖感
     */
    private double warmToneEnhancement = 1.3;

    /**
     * 饱和度增强系数 (0.0-2.0)
     * 让颜色更加鲜艳生动
     */
    private double saturationBoost = 1.4;

    /**
     * 柔光强度 (0.0-1.0)
     * 模拟动画中的柔和光线效果
     */
    private double softLightIntensity = 0.3;

    /**
     * 对比度调整 (0.0-2.0)
     * 保持细节的同时营造氛围
     */
    private double contrastAdjustment = 1.1;

    /**
     * 亮度提升 (-50 到 50)
     * 整体亮度调整
     */
    private int brightnessBoost = 8;

    /**
     * 边缘柔化强度 (0.0-1.0)
     * 减少锐利边缘，增加手绘感
     */
    private double edgeSofteningStrength = 0.4;

    /**
     * 是否启用梦幻效果
     */
    private boolean dreamyEffectEnabled = true;

    /**
     * 梦幻效果强度 (0.0-1.0)
     */
    private double dreamyEffectStrength = 0.2;

    @Override
    /** 获取Image格式化 */
    public String getImageFormat() {
        
        return "jpeg";
    
    }

    @Override
    /** 获取Image格式化 */
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
            dst = new BufferedImage(width, height, src.getType());
        }

        // 第一步：暖色调调整
        BufferedImage warmToned = applyWarmTone(src);
        
        // 第二步：饱和度增强
        BufferedImage saturated = enhanceSaturation(warmToned);
        
        // 第三步：柔光效果
        BufferedImage softLit = applySoftLight(saturated);
        
        // 第四步：对比度和亮度调整
        BufferedImage contrastAdjusted = adjustContrastAndBrightness(softLit);
        
        // 第五步：边缘柔化
        BufferedImage edgeSoftened = applySoftEdges(contrastAdjusted);
        
        // 第六步：梦幻效果（可选）
        BufferedImage finalImage = edgeSoftened;
        if (dreamyEffectEnabled) {
            finalImage = applyDreamyEffect(edgeSoftened);
        }

        // 复制最终结果到目标图像
        dst.getGraphics().drawImage(finalImage, 0, 0, null);
        return dst;
    }

    /**
     * 应用暖色调效果
     */
    private BufferedImage applyWarmTone(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 增强暖色调：增加红色和黄色成分
                red = clamp((int) (red * warmToneEnhancement));
                green = clamp((int) (green * (1.0 + (warmToneEnhancement - 1.0) * 0.7)));
                blue = clamp((int) (blue * (1.0 + (warmToneEnhancement - 1.0) * 0.3)));

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 增强饱和度
     */
    private BufferedImage enhanceSaturation(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 转换到HSV色彩空间进行饱和度调整
                float[] hsv = rgbToHsv(red, green, blue);
                hsv[1] = Math.min(1.0f, (float) (hsv[1] * saturationBoost));

                // 转换回RGB
                int[] newRgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);
                
                int finalRgb = (alpha << 24) | (newRgb[0] << 16) | (newRgb[1] << 8) | newRgb[2];
                result.setRGB(x, y, finalRgb);
            }
        }

        return result;
    }

    /**
     * 应用柔光效果
     */
    private BufferedImage applySoftLight(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 柔光效果：混合原色和高亮色
                double softFactor = softLightIntensity;
                red = clamp((int) (red * (1 - softFactor) + 255 * softFactor * (red / 255.0) * (red / 255.0)));
                green = clamp((int) (green * (1 - softFactor) + 255 * softFactor * (green / 255.0) * (green / 255.0)));
                blue = clamp((int) (blue * (1 - softFactor) + 255 * softFactor * (blue / 255.0) * (blue / 255.0)));

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 调整对比度和亮度
     */
    private BufferedImage adjustContrastAndBrightness(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 应用对比度和亮度调整
                red = clamp((int) ((red - 128) * contrastAdjustment + 128 + brightnessBoost));
                green = clamp((int) ((green - 128) * contrastAdjustment + 128 + brightnessBoost));
                blue = clamp((int) ((blue - 128) * contrastAdjustment + 128 + brightnessBoost));

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 应用边缘柔化
     */
    private BufferedImage applySoftEdges(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        // 简单的高斯模糊核
        double[][] kernel = {
            {0.0625, 0.125, 0.0625},
            {0.125, 0.25, 0.125},
            {0.0625, 0.125, 0.0625}
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

                // 混合原图和模糊图
                int originalRgb = src.getRGB(x, y);
                int originalRed = (originalRgb >> 16) & 0xFF;
                int originalGreen = (originalRgb >> 8) & 0xFF;
                int originalBlue = originalRgb & 0xFF;

                int newRed = clamp((int) (originalRed * (1 - edgeSofteningStrength) + redSum * edgeSofteningStrength));
                int newGreen = clamp((int) (originalGreen * (1 - edgeSofteningStrength) + greenSum * edgeSofteningStrength));
                int newBlue = clamp((int) (originalBlue * (1 - edgeSofteningStrength) + blueSum * edgeSofteningStrength));

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

    /**
     * 应用梦幻效果
     */
    private BufferedImage applyDreamyEffect(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 梦幻效果：添加轻微的光晕
                double centerX = width / 2.0;
                double centerY = height / 2.0;
                double distance = Math.sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY));
                double maxDistance = Math.sqrt(centerX * centerX + centerY * centerY);
                double vignette = 1.0 - (distance / maxDistance) * dreamyEffectStrength;

                red = clamp((int) (red * vignette + 255 * (1 - vignette) * dreamyEffectStrength * 0.3));
                green = clamp((int) (green * vignette + 255 * (1 - vignette) * dreamyEffectStrength * 0.2));
                blue = clamp((int) (blue * vignette + 255 * (1 - vignette) * dreamyEffectStrength * 0.1));

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    @Override
    /** Converter */
    public OutputStream converter(InputStream image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        byte[] buffer = new byte[1024];
        int bytesRead;
        ByteArrayOutputStream tempOutput = new ByteArrayOutputStream();
        while ((bytesRead = image.read(buffer)) != -1) {
            tempOutput.write(buffer, 0, bytesRead);
        }
        
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
     * 限制值在0-255范围内
     */
    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /**
     * RGB转HSV色彩空间
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
            clamp(r + m),
            clamp(g + m),
            clamp(b + m)
        };
    }
}

