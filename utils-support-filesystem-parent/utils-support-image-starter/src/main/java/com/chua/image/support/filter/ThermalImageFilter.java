package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 热成像滤镜
 * <p>
 * 模拟红外热成像（thermal imaging）的伪彩映射：
 * 1. 取亮度作为"温度"
 * 2. 映射到铁红-白热（ironbow）色带：黑→深蓝→紫→红→橙→黄→白
 * 3. 可选加色带（jet：蓝→青→绿→黄→红）
 * 4. 轻微模糊（热成像分辨率低）
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认热成像（ironbow）
 * BufferedImage th = new ThermalImageFilter().converter(src);
 *
 * // jet 色带
 * ThermalImageFilter filter = new ThermalImageFilter()
 *         .setPalette(Palette.JET);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>palette</b>（默认 IRONBOW）：伪彩色带</li>
 *   <li><b>contrast</b>（默认 1.3，范围 1.0-4.0）：温度对比度（拉伸）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>结果单色映射，彩色信息丢失</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("thermal")
@SpiDescribe("热成像滤镜")
@Accessors(chain = true)
public class ThermalImageFilter extends AbstractImageFilter {

    /**
    * 伪彩色带
    */
    public enum Palette {
        /** 白热（黑→白） */
        WHITEHOT,
        /** 铁红（黑→蓝→紫→红→橙→黄→白） */
        IRONBOW,
        /** Jet（蓝→青→绿→黄→红） */
        JET
    }

    /**
        * 色带，默认 IRONBOW
        */
    private Palette palette = Palette.IRONBOW;

    /**
    * 温度对比度，默认 1.3
    */
    private double contrast = 1.3;

    /**
    * 执行热成像滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 热成像效果图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        for (int i = 0; i < argb.length; i++) {
            int gray = ImageProcessorUtils.luminance(argb[i]);
            // 拉伸
            int v = (int) (((gray - 128) * contrast + 128) / 255.0 * 255);
            int t = ImageProcessorUtils.clamp(v);
            int[] rgb = mapPalette(t);
            outPixels[i] = (0xff << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
    * 按色带映射温度(0-255)到 RGB
    *
    * @param t 温度 0-255
    * @return [r, g, b]
    */
    private int[] mapPalette(int t) {
        double x = t / 255.0;
        int r, g, b;
        switch (palette) {
            case WHITEHOT:
                r = g = b = t;
                break;
            case JET:
                // 蓝(0)→青(0.25)→绿(0.5)→黄(0.75)→红(1)
                if (x < 0.125) {
                    r = 0;
                    g = 0;
                    b = (int) (255 * x * 8);
                } else if (x < 0.375) {
                    r = 0;
                    g = (int) (255 * (x - 0.125) * 4);
                    b = 255;
                } else if (x < 0.625) {
                    r = 0;
                    g = 255;
                    b = (int) (255 * (0.625 - x) * 4);
                } else if (x < 0.875) {
                    r = (int) (255 * (x - 0.625) * 4);
                    g = 255;
                    b = 0;
                } else {
                    r = 255;
                    g = (int) (255 * (0.875 - x + 0.125) * 8);
                    b = 0;
                }
                break;
            case IRONBOW:
            default:
                // 黑→深蓝→紫→红→橙→黄→白
                if (x < 0.1) {
                    r = (int) (90 * x * 10);
                    g = 0;
                    b = (int) (180 * x * 10);
                } else if (x < 0.3) {
                    r = (int) (90 + (140 - 90) * (x - 0.1) * 5);
                    g = (int) (40 * (x - 0.1) * 5);
                    b = (int) (180 - 100 * (x - 0.1) * 5);
                } else if (x < 0.55) {
                    r = (int) (140 + (240 - 140) * (x - 0.3) / 0.25);
                    g = (int) (40 + (80 - 40) * (x - 0.3) / 0.25);
                    b = (int) (80 - 80 * (x - 0.3) / 0.25);
                } else if (x < 0.8) {
                    r = 240;
                    g = (int) (80 + (220 - 80) * (x - 0.55) / 0.25);
                    b = 0;
                } else {
                    r = 255;
                    g = 255;
                    b = (int) ((x - 0.8) * 5 * 255);
                }
                break;
        }
        return new int[]{
                ImageProcessorUtils.clamp(r),
                ImageProcessorUtils.clamp(g),
                ImageProcessorUtils.clamp(b)
        };
    }
}
