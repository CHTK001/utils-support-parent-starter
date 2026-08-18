package com.chua.common.support.image.phnatom;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 生产黑白幻影坦克图片
 *
 * @author CH
 * @since 4.0.0.42
*/
public class WhiteAndBlackTankUtils {
    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(WhiteAndBlackTankUtils.class);

    /**
     * 生产黑白幻影坦克图片
     *
     * @param outsideImg 表
     * @param insideImg  里
     * @param path       生成路径
     */
    public static void run(File outsideImg, File insideImg, String path) {
        try {
            Color[][] outside = ImgRgbUtils.getPixels(ImageIO.read(outsideImg));
            Color[][] inside = ImgRgbUtils.getPixels(ImageIO.read(insideImg));
            //拉伸表图成统一大小
            int hl = inside.length;
            int wl = inside[0].length;
            if (hl!=outside.length||wl != outside[0].length){
                outside = ImgRgbUtils.getPixels(ImgRgbUtils.resizeImage(ImageIO.read(outsideImg), wl, hl));
            }
            GetGrayScale(outside, OutsideConfig.A, OutsideConfig.R, OutsideConfig.G, OutsideConfig.B, OutsideConfig.light);
            GetGrayScale(inside, InsideConfig.A, InsideConfig.R, InsideConfig.G, InsideConfig.B, InsideConfig.light);
            Color[][] colors = GrayMerge(outside, inside);
            BufferedImage bufferedImage = ImgRgbUtils.toImage(colors);
            ImageIO.write(bufferedImage, "png", new File(path));
        } catch (Exception e) {
            log.error("Failed to generate white and black phantom tank image", e);
        }
    }

    /**
     * 生成黑白幻影坦克
     *
     * @param colorsF 表图
     * @param colorsB 里图
     * @return
     */
    private static Color[][] GrayMerge(Color[][] colorsF, Color[][] colorsB) {
        int h = colorsF.length;
        int w = colorsF[0].length;
        Color[][] colors = new Color[h][w];
        for (int x = 0; x < colorsF.length; x++) {
            for (int y = 0; y < colorsF[x].length; y++) {
                int alpha = 255 - colorsF[x][y].getRed() + colorsB[x][y].getRed();
                if (alpha > 255) {
                    alpha = 255;
                } else if (alpha == 0) {
                    alpha = 1;
                }
                int gray = 255 * colorsB[x][y].getRed() / alpha;
                if (gray > 255) {
                    gray = 255;
                }
                colors[x][y] = new Color(gray, gray, gray, alpha);
            }
        }
        return colors;
    }

    /**
     * 图片转灰度图
     *
     * @param colors
     * @param A      Alpha倍数
     * @param R      Red比重
     * @param G      Green比重
     * @param B      Blue比重
     * @param Light  亮度
     * @return
     */
    private static void GetGrayScale(Color[][] colors, float A, float R, float G, float B, float Light) {
        float r = R / (R + G + B);
        float g = G / (R + G + B);
        float b = B / (R + G + B);
        for (int x = 0; x < colors.length; x++) {
            for (int y = 0; y < colors[x].length; y++) {
                Color color = colors[x][y];
                int gray = (int) ((color.getRed() * r + color.getGreen() * g + color.getBlue() * b) * Light);
                if (gray > 255) {
                    gray = 255;
                }
                int alpha = (int) (color.getAlpha() * A);
                if (alpha > 255) {
                    alpha = 255;
                }
                colors[x][y] = new Color(gray, gray, gray, alpha);
            }
        }
    }
}
