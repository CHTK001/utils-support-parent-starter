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
 * 吉卜力工作室风格滤镜
 * <p>
 * 专门模拟吉卜力工作室动画电影的精致画风特点：
 * 1. 精细的色彩分层处理
 * 2. 手绘质感模拟
 * 3. 自然光影效果
 * 4. 温暖而丰富的色调
 * 5. 细腻的边缘处理
 * 6. 梦幻氛围营造
 * 
 * 算法特点：
 * - 多层色彩处理：模拟手绘动画的分层上色
 * - 自然光影：模拟自然光线的柔和过渡
 * - 色彩和谐：创造温暖和谐的色彩搭配
 * - 边缘艺术化：模拟手绘线条的自然感
 * - 氛围渲染：营造吉卜力特有的梦幻氛围
 *
 * @author CH
 * @since 2024/12/20
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("ghibli-studio")
@SpiDescribe("吉卜力工作室风格滤镜")
@Accessors(chain = true)
public class GhibliStudioImageFilter extends AbstractImageFilter {

    /**
     * 色彩分层强度 (0.0-1.0)
     * 控制色彩分层的明显程度
     */
    private double colorLayeringStrength = 0.7;

    /**
     * 手绘质感强度 (0.0-1.0)
     * 模拟手绘纹理的强度
     */
    private double handDrawnTextureStrength = 0.4;

    /**
     * 自然光影强度 (0.0-1.0)
     * 模拟自然光线效果的强度
     */
    private double naturalLightingStrength = 0.6;

    /**
     * 暖色调增强 (0.0-2.0)
     * 增强暖色调的程度
     */
    private double warmToneEnhancement = 1.4;

    /**
     * 饱和度提升 (0.0-2.0)
     * 提升色彩饱和度
     */
    private double saturationBoost = 1.3;

    /**
     * 对比度柔化 (0.0-1.0)
     * 柔化对比度的程度
     */
    private double contrastSoftening = 0.3;

    /**
     * 边缘艺术化强度 (0.0-1.0)
     * 边缘艺术化处理的强度
     */
    private double edgeArtisticStrength = 0.5;

    /**
     * 梦幻氛围强度 (0.0-1.0)
     * 梦幻氛围效果的强度
     */
    private double dreamyAtmosphereStrength = 0.3;

    /**
     * 亮度微调 (-20 到 20)
     * 整体亮度的微调
     */
    private int brightnessAdjustment = 8;

    /**
     * 色彩和谐度 (0.0-1.0)
     * 色彩和谐处理的强度
     */
    private double colorHarmonyStrength = 0.6;

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

        // 第一步：色彩分层处理
        BufferedImage layered = applyColorLayering(src);

        // 第二步：暖色调和饱和度增强
        BufferedImage enhanced = enhanceWarmToneAndSaturation(layered);

        // 第三步：自然光影效果
        BufferedImage lit = applyNaturalLighting(enhanced);

        // 第四步：对比度柔化
        BufferedImage softened = applySoftContrast(lit);

        // 第五步：边缘艺术化
        BufferedImage artistic = applyArtisticEdges(softened);

        // 第六步：手绘质感
        BufferedImage textured = applyHandDrawnTexture(artistic);

        // 第七步：色彩和谐处理
        BufferedImage harmonized = applyColorHarmony(textured);

        // 第八步：梦幻氛围
        BufferedImage finalImage = applyDreamyAtmosphere(harmonized);

        // 复制最终结果到目标图像
        dst.getGraphics().drawImage(finalImage, 0, 0, null);
        return dst;
    }

    /**
     * 应用色彩分层处理
     * 模拟动画中的分层上色技术
     */
    private BufferedImage applyColorLayering(BufferedImage src) {
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

                // 色彩分层：将颜色量化到特定层次
                int layers = (int) (8 + colorLayeringStrength * 8); // 8-16层
                double factor = 255.0 / (layers - 1);

                red = (int) (Math.round(red / factor) * factor);
                green = (int) (Math.round(green / factor) * factor);
                blue = (int) (Math.round(blue / factor) * factor);

                // 添加轻微的色彩偏移来模拟手绘感
                double offset = colorLayeringStrength * 5;
                red = ImageProcessorUtils.clamp((int) (red + (Math.sin(x * 0.1 + y * 0.1) * offset)));
                green = ImageProcessorUtils.clamp((int) (green + (Math.cos(x * 0.1 + y * 0.1) * offset)));
                blue = ImageProcessorUtils.clamp((int) (blue + (Math.sin(x * 0.15 + y * 0.05) * offset)));

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 增强暖色调和饱和度
     */
    private BufferedImage enhanceWarmToneAndSaturation(BufferedImage src) {
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

                // 转换到HSV进行处理
                float[] hsv = rgbToHsv(red, green, blue);

                // 暖色调增强：向暖色方向偏移
                if (hsv[1] > 0.1) {
                    // 向红橙黄方向偏移
                    float hueShift = (float) (warmToneEnhancement - 1.0) * 0.05f;
                    hsv[0] = hsv[0] - hueShift;
                    if (hsv[0] < 0) {
                        hsv[0] += 1.0f;
                    }
                }

                // 饱和度提升
                hsv[1] = Math.min(1.0f, (float) (hsv[1] * saturationBoost));

                // 亮度微调
                hsv[2] = Math.min(1.0f, hsv[2] + brightnessAdjustment / 255.0f);

                // 转换回RGB
                int[] newRgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);

                int finalRgb = (alpha << 24) | (newRgb[0] << 16) | (newRgb[1] << 8) | newRgb[2];
                result.setRGB(x, y, finalRgb);
            }
        }

        return result;
    }

    /**
     * 应用自然光影效果
     */
    private BufferedImage applyNaturalLighting(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        // 模拟从左上角来的自然光
        double centerX = width * 0.3;
        double centerY = height * 0.2;
        double maxDistance = Math.sqrt(width * width + height * height);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);

                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 计算距离光源的距离
                double distance = Math.sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY));
                double lightFactor = 1.0 - (distance / maxDistance) * naturalLightingStrength * 0.3;
                lightFactor = Math.max(0.7, Math.min(1.3, lightFactor));

                // 应用光照效果
                red = ImageProcessorUtils.clamp((int) (red * lightFactor));
                green = ImageProcessorUtils.clamp((int) (green * lightFactor));
                blue = ImageProcessorUtils.clamp((int) (blue * lightFactor));

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 应用柔和对比度
     */
    private BufferedImage applySoftContrast(BufferedImage src) {
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

                // 柔化对比度
                double contrastFactor = 1.0 - contrastSoftening * 0.5;
                red = ImageProcessorUtils.clamp((int) ((red - 128) * contrastFactor + 128));
                green = ImageProcessorUtils.clamp((int) ((green - 128) * contrastFactor + 128));
                blue = ImageProcessorUtils.clamp((int) ((blue - 128) * contrastFactor + 128));

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 应用艺术化边缘处理
     */
    private BufferedImage applyArtisticEdges(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        // 艺术化边缘检测和处理
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int centerRgb = src.getRGB(x, y);

                // 计算边缘强度
                double edgeStrength = calculateArtisticEdgeStrength(src, x, y);

                if (edgeStrength > 0.1) {
                    // 对边缘区域进行艺术化处理
                    int alpha = (centerRgb >> 24) & 0xFF;
                    int red = (centerRgb >> 16) & 0xFF;
                    int green = (centerRgb >> 8) & 0xFF;
                    int blue = centerRgb & 0xFF;

                    // 增强边缘的色彩饱和度
                    float[] hsv = rgbToHsv(red, green, blue);
                    hsv[1] = Math.min(1.0f, (float) (hsv[1] * (1 + edgeArtisticStrength * 0.3)));

                    int[] newRgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);
                    int finalRgb = (alpha << 24) | (newRgb[0] << 16) | (newRgb[1] << 8) | newRgb[2];
                    result.setRGB(x, y, finalRgb);
                } else {
                    result.setRGB(x, y, centerRgb);
                }
            }
        }

        // 处理边缘像素
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
     * 计算艺术化边缘强度
     */
    private double calculateArtisticEdgeStrength(BufferedImage src, int x, int y) {
        int centerRgb = src.getRGB(x, y);
        int centerGray = ImageProcessorUtils.luminance(centerRgb);

        double totalDiff = 0;
        int count = 0;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int neighborRgb = src.getRGB(x + dx, y + dy);
                int neighborGray = ImageProcessorUtils.luminance(neighborRgb);
                double diff = Math.abs(centerGray - neighborGray) / 255.0;
                totalDiff += diff;
                count++;
            }
        }

        return totalDiff / count;
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
     * 应用手绘质感
     */
    private BufferedImage applyHandDrawnTexture(BufferedImage src) {
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

                // 添加手绘质感：基于位置的微妙变化
                if (handDrawnTextureStrength > 0) {
                    double textureX = Math.sin(x * 0.02) * Math.cos(y * 0.03);
                    double textureY = Math.cos(x * 0.03) * Math.sin(y * 0.02);
                    double texture = (textureX + textureY) * handDrawnTextureStrength * 8;

                    red = ImageProcessorUtils.clamp((int) (red + texture));
                    green = ImageProcessorUtils.clamp((int) (green + texture * 0.8));
                    blue = ImageProcessorUtils.clamp((int) (blue + texture * 0.6));
                }

                int newRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, newRgb);
            }
        }

        return result;
    }

    /**
     * 应用色彩和谐处理
     */
    private BufferedImage applyColorHarmony(BufferedImage src) {
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

                // 色彩和谐：向特定色调偏移
                if (colorHarmonyStrength > 0) {
                    float[] hsv = rgbToHsv(red, green, blue);

                    // 向和谐色调偏移（暖色调）
                    if (hsv[1] > 0.2) {
                        double harmonyShift = colorHarmonyStrength * 0.03;
                        hsv[0] = (float) (hsv[0] - harmonyShift);
                        if (hsv[0] < 0) {
                            hsv[0] += 1.0f;
                        }
                        // 轻微降低饱和度以增加和谐感
                        hsv[1] = (float) (hsv[1] * (1 - colorHarmonyStrength * 0.1));
                    }

                    int[] newRgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);
                    red = newRgb[0];
                    green = newRgb[1];
                    blue = newRgb[2];
                }

                int finalRgb = (alpha << 24) | (red << 16) | (green << 8) | blue;
                result.setRGB(x, y, finalRgb);
            }
        }

        return result;
    }

    /**
     * 应用梦幻氛围
     */
    private BufferedImage applyDreamyAtmosphere(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, src.getType());

        double centerX = width / 2.0;
        double centerY = height / 2.0;
        double maxDistance = Math.sqrt(centerX * centerX + centerY * centerY);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);

                int alpha = (rgb >> 24) & 0xFF;
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // 梦幻效果：径向光晕
                if (dreamyAtmosphereStrength > 0) {
                    double distance = Math.sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY));
                    double vignette = 1.0 - (distance / maxDistance) * dreamyAtmosphereStrength * 0.4;
                    vignette = Math.max(0.6, Math.min(1.2, vignette));

                    // 添加轻微的暖色光晕
                    red = ImageProcessorUtils.clamp((int) (red * vignette + dreamyAtmosphereStrength * 15));
                    green = ImageProcessorUtils.clamp((int) (green * vignette + dreamyAtmosphereStrength * 10));
                    blue = ImageProcessorUtils.clamp((int) (blue * vignette + dreamyAtmosphereStrength * 5));
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
                ImageProcessorUtils.clamp(r + m),
                ImageProcessorUtils.clamp(g + m),
                ImageProcessorUtils.clamp(b + m)
        };
    }

}

