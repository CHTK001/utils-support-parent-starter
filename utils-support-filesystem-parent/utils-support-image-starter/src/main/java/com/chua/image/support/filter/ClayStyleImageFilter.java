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
import java.util.Random;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 黏土风格滤镜
 * <p>
 * 模拟黏土材质的视觉效果，创造柔和、温暖的定格动画风格：
 * 1. 表面平滑化处理
 * 2. 颜色简化和统一
 * 3. 暖色调调整
 * 4. 对比度柔化
 * 5. 黏土质感模拟
 * 
 * 算法特点：
 * - 双边滤波：保持边缘的同时平滑表面
 * - 颜色量化：减少颜色层次，营造统一感
 * - 暖色调增强：模拟黏土的自然色彩
 * - 对比度降低：营造柔和的视觉效果
 * - 质感添加：模拟黏土的细微粗糙感
 *
 * @author CH
 * @since 2024/12/20
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("clay")
@SpiDescribe("黏土风格滤镜")
@Accessors(chain = true)
public class ClayStyleImageFilter extends AbstractImageFilter {

    /**
     * 表面平滑强度 (0.0-1.0)
     * 控制表面平滑化的程度
     */
    private double smoothingStrength = 0.6;

    /**
     * 颜色简化级别 (4-32)
     * 每个颜色通道的量化级别，值越小颜色越简化
     */
    private int colorSimplificationLevel = 12;

    /**
     * 暖色调偏移 (0.0-1.0)
     * 向暖色调方向的偏移程度
     */
    private double warmToneShift = 0.3;

    /**
     * 对比度降低程度 (0.0-1.0)
     * 降低对比度以营造柔和感
     */
    private double contrastReduction = 0.3;

    /**
     * 黏土质感强度 (0.0-1.0)
     * 添加黏土质感的强度
     */
    private double clayTextureStrength = 0.2;

    /**
     * 边缘保持强度 (0.0-1.0)
     * 在平滑时保持边缘的程度
     */
    private double edgePreservation = 0.4;

    /**
     * 亮度调整 (-30 到 30)
     * 整体亮度微调
     */
    private int brightnessAdjustment = 5;

    /**
     * 饱和度调整 (0.5-1.5)
     * 调整颜色饱和度
     */
    private double saturationAdjustment = 0.9;

    private Random random = new Random();

    @Override
    public String getImageFormat() {
        
        return "jpeg";
    
    }

    @Override
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
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int width = src.getWidth();
        int height = src.getHeight();

        if (dst == null) {
            dst = new BufferedImage(width, height, src.getType());
        }

        // 第一步：表面平滑化
        BufferedImage smoothed = applySurfaceSmoothing(src);

        // 第二步：颜色简化
        BufferedImage simplified = simplifyColors(smoothed);

        // 第三步：暖色调调整
        BufferedImage warmToned = adjustWarmTone(simplified);

        // 第四步：对比度和饱和度调整
        BufferedImage contrastAdjusted = adjustContrastAndSaturation(warmToned);

        // 第五步：添加黏土质感
        BufferedImage finalImage = addClayTexture(contrastAdjusted);

        // 复制最终结果到目标图像
        dst.getGraphics().drawImage(finalImage, 0, 0, null);
        return dst;
    }

    /**
     * 应用表面平滑化
     * 使用简化的双边滤波保持边缘的同时平滑表面
     */
    private BufferedImage applySurfaceSmoothing(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        // 多次高斯模糊来模拟双边滤波效果
        BufferedImage temp = new BufferedImage(width, height, src.getType());

        // 第一次模糊
        applyGaussianBlur(src, temp, smoothingStrength);

        // 边缘检测和保持
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int originalRgb = src.getRGB(x, y);
                int blurredRgb = temp.getRGB(x, y);

                // 计算边缘强度
                double edgeStrength = calculateEdgeStrength(src, x, y);

                // 根据边缘强度混合原图和模糊图
                double blendFactor = Math.max(0, 1 - edgeStrength * edgePreservation);
                int finalRgb = blendColors(originalRgb, blurredRgb, blendFactor);

                result.setRGB(x, y, finalRgb);
            }
        }

        // 处理边缘像素
        for (int x = 0; x < width; x++) {
            result.setRGB(x, 0, temp.getRGB(x, 0));
            result.setRGB(x, height - 1, temp.getRGB(x, height - 1));
        }
        for (int y = 0; y < height; y++) {
            result.setRGB(0, y, temp.getRGB(0, y));
            result.setRGB(width - 1, y, temp.getRGB(width - 1, y));
        }

        return result;
    }

    /**
     * 应用高斯模糊
     */
    private void applyGaussianBlur(BufferedImage src, BufferedImage dst, double strength) {
        int width = src.getWidth();
        int height = src.getHeight();

        // 简化的高斯核
        double[][] kernel = {
                { 0.0625 * strength, 0.125 * strength, 0.0625 * strength },
                { 0.125 * strength, 0.25 * strength, 0.125 * strength },
                { 0.0625 * strength, 0.125 * strength, 0.0625 * strength }
        };

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                double redSum = 0, greenSum = 0, blueSum = 0;
                int alpha = (src.getRGB(x, y) >> 24) & 0xFF;

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

                // 混合原图和模糊结果
                int originalRgb = src.getRGB(x, y);
                int originalRed = (originalRgb >> 16) & 0xFF;
                int originalGreen = (originalRgb >> 8) & 0xFF;
                int originalBlue = originalRgb & 0xFF;

                int newRed = clamp((int) (originalRed * (1 - strength) + redSum));
                int newGreen = clamp((int) (originalGreen * (1 - strength) + greenSum));
                int newBlue = clamp((int) (originalBlue * (1 - strength) + blueSum));

                int newRgb = (alpha << 24) | (newRed << 16) | (newGreen << 8) | newBlue;
                dst.setRGB(x, y, newRgb);
            }
        }

        // 复制边缘
        for (int x = 0; x < width; x++) {
            dst.setRGB(x, 0, src.getRGB(x, 0));
            dst.setRGB(x, height - 1, src.getRGB(x, height - 1));
        }
        for (int y = 0; y < height; y++) {
            dst.setRGB(0, y, src.getRGB(0, y));
            dst.setRGB(width - 1, y, src.getRGB(width - 1, y));
        }
    }

    /**
     * 计算边缘强度
     */
    private double calculateEdgeStrength(BufferedImage src, int x, int y) {
        int centerRgb = src.getRGB(x, y);
        int centerGray = rgbToGray(centerRgb);

        double maxDiff = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int neighborRgb = src.getRGB(x + dx, y + dy);
                int neighborGray = rgbToGray(neighborRgb);
                double diff = Math.abs(centerGray - neighborGray) / 255.0;
                maxDiff = Math.max(maxDiff, diff);
            }
        }

        return maxDiff;
    }

    /**
     * RGB转灰度
     */
    private int rgbToGray(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return (int) (red * 0.299 + green * 0.587 + blue * 0.114);
    }

    /**
     * 混合两个颜色
     */
    private int blendColors(int color1, int color2, double factor) {
        int alpha1 = (color1 >> 24) & 0xFF;
        int red1 = (color1 >> 16) & 0xFF;
        int green1 = (color1 >> 8) & 0xFF;
        int blue1 = color1 & 0xFF;

        int alpha2 = (color2 >> 24) & 0xFF;
        int red2 = (color2 >> 16) & 0xFF;
        int green2 = (color2 >> 8) & 0xFF;
        int blue2 = color2 & 0xFF;

        int newAlpha = (int) (alpha1 * (1 - factor) + alpha2 * factor);
        int newRed = (int) (red1 * (1 - factor) + red2 * factor);
        int newGreen = (int) (green1 * (1 - factor) + green2 * factor);
        int newBlue = (int) (blue1 * (1 - factor) + blue2 * factor);

        return (newAlpha << 24) | (newRed << 16) | (newGreen << 8) | newBlue;
    }

    /**
     * 简化颜色
     */
    private BufferedImage simplifyColors(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        double factor = 255.0 / (colorSimplificationLevel - 1);

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

                red = clamp(red);
                green = clamp(green);
                blue = clamp(blue);

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 调整暖色调
     */
    private BufferedImage adjustWarmTone(BufferedImage src) {
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

                // 转换到HSV进行色调调整
                float[] hsv = rgbToHsv(red, green, blue);

                // 向暖色调偏移（减少色相值，向红橙方向）
                // v[1] > 0.1) { // 只对有饱和度的颜色进行调整
if (hsv[1] > 0.1) {
                hsv[0] = (float) (hsv[0] - warmToneShift * 0.1);
                if (hsv[0] < 0) {
                    hsv[0] += 1.0f;
                }
            }

                // 转换回RGB
                int[] newRgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);

                int finalRgb = (alpha << 24) | (newRgb[0] << 16) | (newRgb[1] << 8) | newRgb[2];
                result.setRGB(x, y, finalRgb);
            }
        }

        return result;
    }

    @Override
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
     * 调整对比度和饱和度
     */
    private BufferedImage adjustContrastAndSaturation(BufferedImage src) {
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

                // 降低对比度
                double contrastFactor = 1.0 - contrastReduction;
                red = clamp((int) ((red - 128) * contrastFactor + 128 + brightnessAdjustment));
                green = clamp((int) ((green - 128) * contrastFactor + 128 + brightnessAdjustment));
                blue = clamp((int) ((blue - 128) * contrastFactor + 128 + brightnessAdjustment));

                // 调整饱和度
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
     * 添加黏土质感
     */
    private BufferedImage addClayTexture(BufferedImage src) {
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

                // 添加轻微的随机噪点来模拟黏土质感
                if (clayTextureStrength > 0) {
                    double noise = (random.nextGaussian() * clayTextureStrength * 10);
                    red = clamp((int) (red + noise));
                    green = clamp((int) (green + noise));
                    blue = clamp((int) (blue + noise));
                }

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
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

        return new float[] { h / 360.0f, s, v };
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
            r = c;
            g = x;
            b = 0;
        } else if (h >= 60 && h < 120) {
            r = x;
            g = c;
            b = 0;
        } else if (h >= 120 && h < 180) {
            r = 0;
            g = c;
            b = x;
        } else if (h >= 180 && h < 240) {
            r = 0;
            g = x;
            b = c;
        } else if (h >= 240 && h < 300) {
            r = x;
            g = 0;
            b = c;
        } else if (h >= 300 && h < 360) {
            r = c;
            g = 0;
            b = x;
        }

        return new int[] {
                clamp(r + m),
                clamp(g + m),
                clamp(b + m)
        };
    }

    /**
     * 限制值在0-255范围内
     */
    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}

