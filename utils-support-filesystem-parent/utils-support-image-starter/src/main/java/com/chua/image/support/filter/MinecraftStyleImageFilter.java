package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
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
 * 我的世界(Minecraft)风格滤镜
 * <p>
 * 模拟Minecraft游戏的像素化画风特点：
 * 1. 像素化效果
 * 2. 颜色量化
 * 3. 方块化处理
 * 4. 对比度增强
 * 5. 饱和度调整
 * 
 * 算法特点：
 * - 像素化：将图像转换为低分辨率的像素块
 * - 颜色量化：减少颜色数量，模拟游戏调色板
 * - 边缘锐化：增强方块边缘的清晰度
 * - 对比度提升：让颜色更加鲜明
 * - 饱和度调整：模拟游戏中的色彩风格
 *
 * @author CH
 * @since 2024/12/20
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("minecraft")
@SpiDescribe("我的世界风格滤镜")
@Accessors(chain = true)
public class MinecraftStyleImageFilter extends AbstractImageFilter {

    /**
     * 像素块大小 (2-20)
     * 控制像素化的程度，值越大越像素化
     */
    private int pixelBlockSize = 8;

    /**
     * 颜色量化级别 (2-8)
     * 每个颜色通道的量化级别，值越小颜色越少
     */
    private int colorQuantizationLevel = 4;

    /**
     * 对比度增强系数 (0.5-3.0)
     * 增强图像对比度，让颜色更加鲜明
     */
    private double contrastEnhancement = 1.5;

    /**
     * 饱和度调整系数 (0.5-2.0)
     * 调整颜色饱和度，模拟游戏风格
     */
    private double saturationAdjustment = 1.3;

    /**
     * 亮度调整 (-50 到 50)
     * 整体亮度调整
     */
    private int brightnessAdjustment = 5;

    /**
     * 是否启用边缘锐化
     */
    private boolean edgeSharpening = true;

    /**
     * 锐化强度 (0.0-2.0)
     */
    private double sharpenStrength = 1.0;

    /**
     * 是否启用方块效果
     * 在像素化基础上增加方块边框效果
     */
    private boolean blockEffect = true;

    /**
     * 方块边框强度 (0.0-1.0)
     */
    private double blockBorderStrength = 0.3;

    @Override
    /** 获取Image格式化 */
    public String getImageFormat() {
        
        return "png";
    
    }

    @Override
    /** 获取Image格式化 */
    public String getImageFormat(String name) {
        if (name == null) {
            return getImageFormat();
        }
        String lowerName = name.toLowerCase();
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            return "jpeg";
        } else if (lowerName.endsWith(".gif")) {
            return "gif";
        } else if (lowerName.endsWith(".bmp")) {
            return "bmp";
        }
        return "png";
    }

    @Override
    /** 过滤 */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int width = src.getWidth();
        int height = src.getHeight();

        if (dst == null) {
            dst = new BufferedImage(width, height, src.getType());
        }

        // 第一步：像素化处理
        BufferedImage pixelated = applyPixelation(src);
        
        // 第二步：颜色量化
        BufferedImage quantized = applyColorQuantization(pixelated);
        
        // 第三步：对比度和饱和度调整
        BufferedImage enhanced = enhanceContrastAndSaturation(quantized);
        
        // 第四步：边缘锐化（可选）
        BufferedImage sharpened = enhanced;
        if (edgeSharpening) {
            sharpened = applySharpen(enhanced);
        }
        
        // 第五步：方块效果（可选）
        BufferedImage finalImage = sharpened;
        if (blockEffect) {
            finalImage = applyBlockEffect(sharpened);
        }

        // 复制最终结果到目标图像
        dst.getGraphics().drawImage(finalImage, 0, 0, null);
        return dst;
    }

    /**
     * 应用像素化效果
     */
    private BufferedImage applyPixelation(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        for (int y = 0; y < height; y += pixelBlockSize) {
            for (int x = 0; x < width; x += pixelBlockSize) {
                // 计算当前块的平均颜色
                long totalRed = 0, totalGreen = 0, totalBlue = 0, totalAlpha = 0;
                int pixelCount = 0;

                for (int dy = 0; dy < pixelBlockSize && y + dy < height; dy++) {
                    for (int dx = 0; dx < pixelBlockSize && x + dx < width; dx++) {
                        int rgb = src.getRGB(x + dx, y + dy);
                        totalAlpha += (rgb >> 24) & 0xFF;
                        totalRed += (rgb >> 16) & 0xFF;
                        totalGreen += (rgb >> 8) & 0xFF;
                        totalBlue += rgb & 0xFF;
                        pixelCount++;
                    }
                }

                // 计算平均颜色
                int avgAlpha = (int) (totalAlpha / pixelCount);
                int avgRed = (int) (totalRed / pixelCount);
                int avgGreen = (int) (totalGreen / pixelCount);
                int avgBlue = (int) (totalBlue / pixelCount);
                int avgColor = (avgAlpha << 24) | (avgRed << 16) | (avgGreen << 8) | avgBlue;

                // 将平均颜色应用到整个块
                for (int dy = 0; dy < pixelBlockSize && y + dy < height; dy++) {
                    for (int dx = 0; dx < pixelBlockSize && x + dx < width; dx++) {
                        result.setRGB(x + dx, y + dy, avgColor);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 应用颜色量化
     */
    private BufferedImage applyColorQuantization(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        int levels = colorQuantizationLevel;
        double factor = 255.0 / (levels - 1);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);
                
                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 量化每个颜色通道
                red = (int) (Math.round(red / factor) * factor);
                green = (int) (Math.round(green / factor) * factor);
                blue = (int) (Math.round(blue / factor) * factor);

                red = ImageProcessorUtils.clamp(red);
                green = ImageProcessorUtils.clamp(green);
                blue = ImageProcessorUtils.clamp(blue);

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 增强对比度和饱和度
     */
    private BufferedImage enhanceContrastAndSaturation(BufferedImage src) {
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

                // 应用对比度增强
                red = ImageProcessorUtils.clamp((int) ((red - 128) * contrastEnhancement + 128 + brightnessAdjustment));
                green = ImageProcessorUtils.clamp((int) ((green - 128) * contrastEnhancement + 128 + brightnessAdjustment));
                blue = ImageProcessorUtils.clamp((int) ((blue - 128) * contrastEnhancement + 128 + brightnessAdjustment));

                // 转换到HSV进行饱和度调整
                float[] hsv = rgbToHsv(red, green, blue);
                hsv[1] = Math.min(1.0f, (float) (hsv[1] * saturationAdjustment));

                // 转换回RGB
                int[] newRgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);
                
                int finalRgb = (alpha << 24) | (newRgb[0] << 16) | (newRgb[1] << 8) | newRgb[2];
                result.setRGB(x, y, finalRgb);
            }
        }

        return result;
    }

    /**
     * 应用锐化效果
     */
    private BufferedImage applySharpen(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

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

    /**
     * 应用方块效果
     */
    private BufferedImage applyBlockEffect(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        // 复制原图
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result.setRGB(x, y, src.getRGB(x, y));
            }
        }

        // 添加方块边框
        for (int y = 0; y < height; y += pixelBlockSize) {
            for (int x = 0; x < width; x += pixelBlockSize) {
                // 绘制方块边框
                for (int dx = 0; dx < pixelBlockSize && x + dx < width; dx++) {
                    if (y < height) {
                        // 上边框
                        int rgb = result.getRGB(x + dx, y);
                        int darkerRgb = darkenColor(rgb, blockBorderStrength);
                        result.setRGB(x + dx, y, darkerRgb);
                    }
                    if (y + pixelBlockSize - 1 < height) {
                        // 下边框
                        int rgb = result.getRGB(x + dx, y + pixelBlockSize - 1);
                        int darkerRgb = darkenColor(rgb, blockBorderStrength);
                        result.setRGB(x + dx, y + pixelBlockSize - 1, darkerRgb);
                    }
                }
                
                for (int dy = 0; dy < pixelBlockSize && y + dy < height; dy++) {
                    if (x < width) {
                        // 左边框
                        int rgb = result.getRGB(x, y + dy);
                        int darkerRgb = darkenColor(rgb, blockBorderStrength);
                        result.setRGB(x, y + dy, darkerRgb);
                    }
                    if (x + pixelBlockSize - 1 < width) {
                        // 右边框
                        int rgb = result.getRGB(x + pixelBlockSize - 1, y + dy);
                        int darkerRgb = darkenColor(rgb, blockBorderStrength);
                        result.setRGB(x + pixelBlockSize - 1, y + dy, darkerRgb);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 使颜色变暗
     */
    private int darkenColor(int rgb, double factor) {
        int alpha = (rgb >> 24) & 0xFF;
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;

        red = ImageProcessorUtils.clamp((int) (red * (1 - factor)));
        green = ImageProcessorUtils.clamp((int) (green * (1 - factor)));
        blue = ImageProcessorUtils.clamp((int) (blue * (1 - factor)));

        return (alpha << 24) | (red << 16) | (green << 8) | blue;
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
            ImageProcessorUtils.clamp(r + m),
            ImageProcessorUtils.clamp(g + m),
            ImageProcessorUtils.clamp(b + m)
        };
    }
}

