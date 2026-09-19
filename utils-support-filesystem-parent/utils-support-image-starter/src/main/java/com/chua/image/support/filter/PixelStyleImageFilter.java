package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;


/**
 * 像素游戏风格滤镜
 *
 * 将图像转换为像素游戏（pixel art）视觉风格，实现图片到像素游戏风的转换。
 *
 * 技术原理：
 * - 像素块化：将图像按 blockSize 网格划分，每个块采用中心像素颜色填充（硬边，非模糊）
 * - 颜色量化：对 RGB 各通道做阶梯量化（quantizationLevels 个色阶），压缩色彩范围，
 *   模拟复古游戏（GBA/8-bit/16-bit）的低色彩调色板
 * - 复古调色板：可选将量化后的颜色吸附到 NES(58色)/GBA(32色)/PS1(64色) 经典调色板，
 *   获得真实的复古游戏配色观感
 * - 可选锐化与描边：像素块边缘锐化、深色描边，强化像素块的视觉边界
 *
 * 视觉效果：
 * - 整体呈现"大像素颗粒 + 复古低色"的游戏像素风
 * - 可通过 quantizationLevels / paletteMode / blockOutlineStrength 调节风格浓度
 *
 * 应用场景：
 * - 图片转像素游戏风（头像、海报、装饰图）
 * - 游戏素材风格统一
 * - 复古 8-bit/16-bit 风格创作
 *
 * 典型用法：
 * <pre>
 * PixelStyleImageFilter filter = new PixelStyleImageFilter()
 *     .setBlockSize(8)
 *     .setQuantizationLevels(4)
 *     .setPaletteMode(PixelStyleImageFilter.PaletteMode.GBA);
 * BufferedImage out = filter.converter(srcImage);
 * </pre>
 *
 * @author CH
 * @version 1.0.0
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("pixel-style")
@SpiDescribe("像素游戏风格滤镜")
@Accessors(chain = true)
public class PixelStyleImageFilter extends AbstractImageFilter {

    /**
     * 像素块大小（网格边长，像素），默认 8
     * 值越大像素颗粒越粗。建议 2 ~ 32。
     */
    private int blockSize = 8;

    /**
     * 每通道量化级数（2 ~ 8），默认 4
     * 每通道 4 级 => 共 64 色，接近 GBA 级别。值越小色彩越复古。
     */
    private int quantizationLevels = 4;

    /**
     * 调色板模式，默认 NONE（纯量化）
     * NES/GBA/PS1 模式下会把量化后的颜色吸附到对应复古调色板
     */
    private PaletteMode paletteMode = PaletteMode.NONE;

    /**
     * 是否启用像素块边缘锐化，默认 true
     */
    private boolean edgeSharpening = true;

    /**
     * 锐化强度 (0.0 - 2.0)，默认 0.5
     */
    private double sharpenStrength = 0.5;

    /**
     * 是否启用块描边（在像素块边界压暗，强化颗粒边界），默认 false
     */
    private boolean blockOutline = false;

    /**
     * 块描边强度 (0.0 - 1.0)，默认 0.25
     */
    private double blockOutlineStrength = 0.25;

    /**
     * 调色板模式
     */
    public enum PaletteMode {
        /**
         * 不吸附调色板，仅做阶梯量化
         */
        NONE,

        /**
         * NES 58 色调色板（经典 NES 配色）
         */
        NES,

        /**
         * GBA 32 色调色板
         */
        GBA,

        /**
         * PS1 风格 64 色调色板
         */
        PS1
    }

    /**
     * 执行像素游戏风格滤镜处理
     *
     * 处理流水线：
     * 1. 像素块化（中心像素填充，硬边）
     * 2. 颜色量化 + 可选调色板吸附
     * 3. 可选边缘锐化
     * 4. 可选块描边
     *
     * 输出保持与源图像相同的宽高。
     *
     * @param src 源图像
     * @param dst 目标图像（可为空，为空时自动创建）
     * @return 应用像素游戏风格后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int width = src.getWidth();
        int height = src.getHeight();

        int safeBlock = Math.max(1, blockSize);
        int safeLevels = Math.max(2, Math.min(8, quantizationLevels));

        // 第一步：像素块化
        BufferedImage pixelated = applyPixelation(src, safeBlock);

        // 第二步：颜色量化 + 调色板吸附
        BufferedImage colorProcessed = applyColorQuantization(pixelated, safeLevels, paletteMode);

        // 第三步：可选边缘锐化
        BufferedImage sharpened = colorProcessed;
        if (edgeSharpening) {
            sharpened = applySharpen(colorProcessed);
        }

        // 第四步：可选块描边
        BufferedImage finalImage = sharpened;
        if (blockOutline) {
            finalImage = applyBlockOutline(sharpened, safeBlock);
        }

        if (dst == null) {
            dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }
        dst.getGraphics().drawImage(finalImage, 0, 0, null);
        return dst;
    }

    /**
     * 应用像素块化效果
     *
     * 按 blockSize 网格划分，每个块使用中心像素颜色填充整块（硬边，非平均，
     * 更接近真实像素画"取整像素"的观感）。
     *
     * @param src  源图像
     * @param block 块大小
     * @return 像素块化后的图像
     */
    private BufferedImage applyPixelation(BufferedImage src, int block) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int[] srcPixels = src.getRGB(0, 0, width, height, null, 0, width);

        for (int y = 0; y < height; y += block) {
            int yEnd = Math.min(y + block, height);
            int centerY = (y + yEnd - 1) / 2;
            for (int x = 0; x < width; x += block) {
                int xEnd = Math.min(x + block, width);
                int centerX = (x + xEnd - 1) / 2;

                int centerColor = srcPixels[centerY * width + centerX];

                for (int dy = y; dy < yEnd; dy++) {
                    for (int dx = x; dx < xEnd; dx++) {
                        result.setRGB(dx, dy, centerColor);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 应用颜色量化与调色板吸附
     *
     * 对 RGB 各通道做阶梯量化，模拟低色彩复古调色板。
     * 若 paletteMode 不为 NONE，则进一步将量化后的颜色吸附到最接近的调色板颜色。
     *
     * @param src     源图像
     * @param levels  每通道量化级数
     * @param mode    调色板模式
     * @return 量化后的图像
     */
    private BufferedImage applyColorQuantization(BufferedImage src, int levels, PaletteMode mode) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int[] pixels = src.getRGB(0, 0, width, height, null, 0, width);
        int step = 255 / (levels - 1);
        int[][] palette = (mode == PaletteMode.NONE) ? null : buildPalette(mode);

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int alpha = (p >> 24) & 0xFF;
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;

            if (alpha == 0) {
                result.setRGB(i % width, i / width, 0);
                continue;
            }

            // 阶梯量化
            r = quantize(r, step);
            g = quantize(g, step);
            b = quantize(b, step);

            if (palette != null) {
                int[] snapped = snapToPalette(palette, r, g, b);
                r = snapped[0];
                g = snapped[1];
                b = snapped[2];
            }

            result.setRGB(i % width, i / width, (0xFF << 24) | (r << 16) | (g << 8) | b);
        }

        return result;
    }

    /**
     * 单通道阶梯量化
     *
     * @param value 原始通道值 (0-255)
     * @param step  量化步长
     * @return 量化后的通道值
     */
    private int quantize(int value, int step) {
        int q = (int) (Math.round(value / (double) step) * step);
        if (q > 255) {
            q = 255;
        }
        return q;
    }

    /**
     * 将颜色吸附到调色板中最接近的颜色（RGB 欧氏距离）
     *
     * @param palette 调色板
     * @param r       红色分量
     * @param g       绿色分量
     * @param b       蓝色分量
     * @return [r, g, b] 吸附后的分量
     */
    private int[] snapToPalette(int[][] palette, int r, int g, int b) {
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int dr = r - palette[i][0];
            int dg = g - palette[i][1];
            int db = b - palette[i][2];
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return palette[bestIndex];
    }

    /**
     * 构建指定模式的复古调色板
     *
     * @param mode 调色板模式
     * @return 调色板（int[colors][3]）
     */
    private int[][] buildPalette(PaletteMode mode) {
        switch (mode) {
            case NES:
                return buildNesPalette();
            case GBA:
                return buildGbaPalette();
            case PS1:
                return buildPs1Palette();
            default:
                return new int[0][];
        }
    }

    /**
     * 构建 NES 风格调色板（基于经典 58 色 NES 调色板中可合成的代表性子集）
     *
     * @return NES 调色板
     */
    private int[][] buildNesPalette() {
        int[][] palette = new int[58][3];
        // 经典 NES 调色板代表性颜色（RGB）
        int[][] classic = {
            {0, 0, 0}, {92, 0, 156}, {188, 0, 128}, {228, 56, 64}, {164, 60, 164},
            {0, 68, 156}, {0, 108, 108}, {0, 132, 44}, {12, 116, 0}, {76, 88, 0},
            {148, 40, 0}, {144, 20, 0}, {88, 28, 0}, {0, 0, 0}, {0, 0, 0},
            {0, 0, 0}, {92, 92, 92}, {188, 96, 96}, {228, 124, 124}, {164, 144, 184},
            {104, 156, 224}, {100, 140, 196}, {88, 132, 144}, {0, 132, 116}, {44, 100, 100},
            {76, 96, 44}, {228, 124, 96}, {228, 164, 124}, {164, 144, 144}, {124, 144, 196},
            {104, 124, 196}, {64, 124, 196}, {0, 124, 196}, {0, 88, 148}, {0, 88, 108},
            {0, 196, 196}, {44, 228, 164}, {100, 228, 164}, {228, 228, 124}, {196, 196, 124},
            {196, 164, 92}, {144, 116, 76}, {124, 92, 68}, {124, 68, 44}, {88, 44, 28},
            {164, 164, 164}, {228, 228, 228}, {228, 196, 144}, {228, 184, 164}, {184, 164, 196},
            {228, 164, 228}, {124, 164, 228}, {144, 144, 228}, {144, 124, 228}, {88, 88, 228},
            {52, 52, 148}, {48, 48, 48}, {96, 60, 0}, {228, 96, 228}, {0, 228, 0}
        };
        for (int i = 0; i < 58; i++) {
            int[] c = classic[i % classic.length];
            palette[i] = new int[]{c[0], c[1], c[2]};
        }
        return palette;
    }

    /**
     * 构建 GBA 风格调色板（32 色，1.5.5 色彩深度经典 GBA 调色板）
     *
     * @return GBA 调色板
     */
    private int[][] buildGbaPalette() {
        int[][] palette = new int[32][3];
        // GBA 经典 32 色调色板（RGB）
        int[][] classic = {
            {0, 0, 0}, {37, 37, 37}, {74, 74, 74}, {111, 111, 111}, {148, 148, 148},
            {185, 185, 185}, {222, 222, 222}, {255, 255, 255},
            {255, 0, 0}, {255, 0, 128}, {255, 0, 255}, {128, 0, 255}, {0, 0, 255},
            {0, 128, 255}, {0, 255, 255}, {0, 255, 128}, {0, 255, 0}, {128, 255, 0},
            {255, 255, 0}, {255, 128, 0}, {255, 64, 0}, {128, 64, 0}, {128, 128, 0},
            {128, 0, 0}, {0, 128, 0}, {0, 64, 0}, {64, 0, 0}, {0, 0, 128}, {64, 64, 64}
        };
        for (int i = 0; i < 32; i++) {
            palette[i] = new int[]{classic[i][0], classic[i][1], classic[i][2]};
        }
        return palette;
    }

    /**
     * 构建 PS1 风格调色板（64 色，模拟 15-bit 色彩的 8-bit 近似）
     *
     * @return PS1 调色板
     */
    private int[][] buildPs1Palette() {
        int[][] palette = new int[64][3];
        // PS1 经典 8-bit 调色板（RGB）
        int[][] classic = {
            {0, 0, 0}, {38, 0, 0}, {0, 38, 0}, {38, 38, 0}, {0, 76, 0}, {38, 76, 0},
            {76, 76, 0}, {0, 76, 38}, {76, 38, 0}, {76, 76, 38}, {76, 38, 76}, {0, 76, 76},
            {38, 0, 38}, {0, 115, 38}, {76, 0, 38}, {76, 0, 76}, {0, 115, 76}, {76, 38, 0},
            {38, 76, 76}, {38, 76, 38}, {38, 0, 76}, {0, 38, 76}, {38, 38, 0}, {0, 153, 0},
            {38, 153, 38}, {38, 153, 76}, {38, 115, 0}, {38, 38, 38}, {0, 153, 76},
            {38, 115, 76}, {76, 153, 0}, {76, 153, 76}, {38, 76, 38}, {76, 76, 76},
            {0, 191, 38}, {38, 191, 76}, {76, 191, 38}, {76, 191, 76}, {0, 191, 115},
            {38, 191, 153}, {76, 191, 153}, {38, 153, 76}, {76, 191, 0}, {0, 153, 38},
            {76, 230, 38}, {0, 230, 76}, {38, 230, 76}, {76, 230, 115}, {0, 191, 153},
            {38, 230, 153}, {76, 230, 191}, {38, 191, 191}, {0, 153, 115}, {38, 115, 115},
            {76, 230, 76}, {115, 230, 115}, {76, 191, 153}, {115, 230, 153}, {76, 230, 153},
            {153, 230, 153}, {115, 191, 76}, {153, 230, 191}, {76, 153, 38}, {115, 191, 38}
        };
        for (int i = 0; i < 64; i++) {
            palette[i] = new int[]{classic[i][0], classic[i][1], classic[i][2]};
        }
        return palette;
    }

    /**
     * 应用边缘锐化
     *
     * 使用 3x3 锐化卷积核增强像素块边界对比度。
     *
     * @param src 源图像
     * @return 锐化后的图像
     */
    private BufferedImage applySharpen(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int[] pixels = src.getRGB(0, 0, width, height, null, 0, width);

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int center = pixels[y * width + x];
                int alpha = (center >> 24) & 0xFF;
                if (alpha == 0) {
                    result.setRGB(x, y, 0);
                    continue;
                }

                int r = 0, g = 0, b = 0;
                // 卷积核: [0, -s, 0; -s, 1+4s, -s; 0, -s, 0]
                r += (pixels[(y - 1) * width + x] >> 16 & 0xFF) * -sharpenStrength;
                r += (pixels[(y + 1) * width + x] >> 16 & 0xFF) * -sharpenStrength;
                r += (pixels[y * width + x - 1] >> 16 & 0xFF) * -sharpenStrength;
                r += (pixels[y * width + x + 1] >> 16 & 0xFF) * -sharpenStrength;
                r += (center >> 16 & 0xFF) * (1 + 4 * sharpenStrength);

                g += (pixels[(y - 1) * width + x] >> 8 & 0xFF) * -sharpenStrength;
                g += (pixels[(y + 1) * width + x] >> 8 & 0xFF) * -sharpenStrength;
                g += (pixels[y * width + x - 1] >> 8 & 0xFF) * -sharpenStrength;
                g += (pixels[y * width + x + 1] >> 8 & 0xFF) * -sharpenStrength;
                g += (center >> 8 & 0xFF) * (1 + 4 * sharpenStrength);

                b += (pixels[(y - 1) * width + x] & 0xFF) * -sharpenStrength;
                b += (pixels[(y + 1) * width + x] & 0xFF) * -sharpenStrength;
                b += (pixels[y * width + x - 1] & 0xFF) * -sharpenStrength;
                b += (pixels[y * width + x + 1] & 0xFF) * -sharpenStrength;
                b += (center & 0xFF) * (1 + 4 * sharpenStrength);

                int nr = clamp(r);
                int ng = clamp(g);
                int nb = clamp(b);
                result.setRGB(x, y, (0xFF << 24) | (nr << 16) | (ng << 8) | nb);
            }
        }

        // 边缘像素直接复制
        for (int x = 0; x < width; x++) {
            result.setRGB(x, 0, pixels[x]);
            result.setRGB(x, height - 1, pixels[(height - 1) * width + x]);
        }
        for (int y = 0; y < height; y++) {
            result.setRGB(0, y, pixels[y * width]);
            result.setRGB(width - 1, y, pixels[y * width + width - 1]);
        }

        return result;
    }

    /**
     * 应用块描边效果
     *
     * 在每个像素块的边界像素上压暗，强化像素颗粒边界。
     *
     * @param src   源图像
     * @param block 块大小
     * @return 描边后的图像
     */
    private BufferedImage applyBlockOutline(BufferedImage src, int block) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int[] pixels = src.getRGB(0, 0, width, height, null, 0, width);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = pixels[y * width + x];
                int alpha = (p >> 24) & 0xFF;
                if (alpha == 0) {
                    result.setRGB(x, y, 0);
                    continue;
                }

                // 处于块边界（块上沿或块左沿）时压暗
                boolean isTopEdge = (y % block == 0);
                boolean isLeftEdge = (x % block == 0);
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;

                if (isTopEdge || isLeftEdge) {
                    double factor = 1 - blockOutlineStrength;
                    r = clamp((int) (r * factor));
                    g = clamp((int) (g * factor));
                    b = clamp((int) (b * factor));
                }

                result.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }

        return result;
    }

    /**
     * 通道值钳制到 0-255
     *
     * @param value 原始值
     * @return 钳制后的值
     */
    private int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }
}
