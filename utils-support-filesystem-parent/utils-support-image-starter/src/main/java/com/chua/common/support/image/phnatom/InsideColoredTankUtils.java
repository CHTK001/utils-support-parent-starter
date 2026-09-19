package com.chua.common.support.image.phnatom;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;


/**
 * 生产外黑白内彩色幻影坦克图
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class InsideColoredTankUtils {

    /**
     * 生产外黑白内彩色幻影坦克图
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
            ImgRgbUtils.GetGrayScaleHalf(inside, InsideConfig.light);
            Color[][] colors = GrayMergeHalf(outside, inside);
            BufferedImage bufferedImage = ImgRgbUtils.toImage(colors);
            ImageIO.write(bufferedImage, "png", new File(path));
        } catch (Exception e) {
            log.error("Failed to generate inside-colored phantom tank image", e);
        }
    }


    /**
     * 生成外黑白内彩幻影坦克
     *
     * @param colorsF 表图
     * @param colorsB 里图
     * @return
     */
    public static Color[][] GrayMergeHalf(Color[][] colorsF, Color[][] colorsB) {
        int h = colorsF.length;
        int w = colorsF[0].length;
        Color[][] colors = new Color[h][w];
        for (int x = 0; x < colorsF.length; x++) {
            for (int y = 0; y < colorsF[x].length; y++) {
                int alpha = 255 - colorsF[x][y].getRed() + colorsB[x][y].getRed();
                alpha = alpha > 255 ? 255 : alpha;
                alpha = alpha == 0 ? 1 : alpha;
                int R = 255 * colorsB[x][y].getRed() / alpha;
                R = R > 255 ? 255 : R;
                int G = 255 * colorsB[x][y].getGreen() / alpha;
                G = G > 255 ? 255 : G;
                int B = 255 * colorsB[x][y].getBlue() / alpha;
                B = B > 255 ? 255 : B;
                colors[x][y] = new Color(R, G, B, alpha);
            }
        }
        return colors;
    }

    /**
     * 图片转灰度图
     *
     * @param colors
     * @param A      Alpha倍数
     * @param R      R比重
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
