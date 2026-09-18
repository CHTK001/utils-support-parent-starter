package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;


/**
 * 波普艺术风格滤镜
 * <p>
 * 模拟 Andy Warhol 的波普艺术（Pop Art）效果：
 * 1. 色块化：将颜色映射到有限的鲜艳调色板
 * 2. 高饱和高对比：颜色比原图更鲜艳、对比更强
 * 3. 网点纹理（可选）：叠加 Ben-Day dots 印刷网点
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认波普风格
 * BufferedImage pop = new PopArtImageFilter().converter(src);
 *
 * // 加网点纹理
 * PopArtImageFilter filter = new PopArtImageFilter()
 *         .setHalftone(true)
 *         .setPaletteSize(4);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>paletteSize</b>（默认 4，范围 2-8）：调色板大小，越小色块越明显</li>
 *   <li><b>saturationBoost</b>（默认 1.8，范围 1.0-3.0）：饱和度增强系数</li>
 *   <li><b>contrast</b>（默认 1.4，范围 1.0-3.0）：对比度增强系数</li>
 *   <li><b>halftone</b>（默认 false）：是否叠加网点纹理</li>
 *   <li><b>halftoneSize</b>（默认 4）：网点间距（像素）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>paletteSize=4 时颜色会被压缩到 4 个鲜艳色块，波普感最强</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("popart")
@SpiDescribe("波普艺术风格滤镜")
@Accessors(chain = true)
public class PopArtImageFilter extends AbstractImageFilter {

    /**
    * 调色板大小，默认 4
    */
    private int paletteSize = 4;

    /**
    * 饱和度增强系数，默认 1.8
    */
    private double saturationBoost = 1.8;

    /**
    * 对比度增强系数，默认 1.4
    */
    private double contrast = 1.4;

    /**
    * 是否叠加网点纹理，默认 false
    */
    private boolean halftone = false;

    /**
    * 网点间距（像素），默认 4
    */
    private int halftoneSize = 4;

    /**
    * 经典波普调色板（RGB）
    */
    private static final int[][] PALETTE_4 = {
            {230, 30, 90},    // 粉红
            {50, 130, 220},   // 蓝
            {250, 200, 40},   // 黄
            {240, 240, 240},   // 浅灰
    };

    /**
    * 执行波普艺术滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 波普风格图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        int[][] palette = pickPalette();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = argb[i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;

                // 饱和度增强
                int lum = (int) (r * 0.299 + g * 0.587 + b * 0.114);
                r = clamp((int) (lum + (r - lum) * saturationBoost));
                g = clamp((int) (lum + (g - lum) * saturationBoost));
                b = clamp((int) (lum + (b - lum) * saturationBoost));

                // 对比度增强
                r = clamp((int) (((r - 128) * contrast + 128)));
                g = clamp((int) (((g - 128) * contrast + 128)));
                b = clamp((int) (((b - 128) * contrast + 128)));

                // 映射到调色板最近色
                int[] nearest = nearestColor(palette, r, g, b);

                // 网点纹理
                int fr = nearest[0], fg = nearest[1], fb = nearest[2];
                if (halftone && halftoneSize > 0) {
                    int dotX = (int) ((x % halftoneSize - halftoneSize / 2) * 1.0);
                    int dotY = (int) ((y % halftoneSize - halftoneSize / 2) * 1.0);
                    int dist = dotX * dotX + dotY * dotY;
                    int radius2 = (halftoneSize / 2 - 1) * (halftoneSize / 2 - 1);
                    if (dist > radius2) {
                        int lum2 = (fr + fg + fb) / 3;
                        int dark = (255 - lum2) / 3;
                        fr = clamp(fr + dark);
                        fg = clamp(fg + dark);
                        fb = clamp(fb + dark);
                    }
                }

                outPixels[i] = (0xff << 24) | (fr << 16) | (fg << 8) | fb;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
    * 根据 paletteSize 选择调色板
    *
    * @return 调色板
    */
    private int[][] pickPalette() {
        if (paletteSize <= 4) {
            return PALETTE_4;
        }
        // 更大的调色板：4 个基础色 + 中间色
        int[][] extra = {
                {255, 120, 180},
                {120, 200, 255},
                {255, 240, 150},
                {200, 200, 220},
        };
        int size = Math.min(paletteSize, 8);
        int[][] result = new int[size][3];
        for (int i = 0; i < size; i++) {
            result[i] = i < 4 ? PALETTE_4[i] : extra[i - 4];
        }
        return result;
    }

    /**
    * 找调色板中最近的颜色
    *
    * @param palette 调色板
    * @param r       红
    * @param g       绿
    * @param b       蓝
    * @return [r, g, b]
    */
    private int[] nearestColor(int[][] palette, int r, int g, int b) {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int dr = r - palette[i][0];
            int dg = g - palette[i][1];
            int db = b - palette[i][2];
            int d = dr * dr + dg * dg + db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return palette[best];
    }

    /**
    * 通道值钳制 0-255
    *
    * @param v 原始值
    * @return 钳制后的值
    */
    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
