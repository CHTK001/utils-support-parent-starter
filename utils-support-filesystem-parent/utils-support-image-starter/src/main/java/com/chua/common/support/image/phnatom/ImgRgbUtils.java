/**
 * 图片 RGB 像素工具类。
 *
 * <p>提供 BufferedImage 与二维像素数组之间的相互转换，
 * 以及图片缩放、亮度调整等常用图像处理操作。</p>
 *
 * @author CH
 */
package com.chua.common.support.image.phnatom;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


public class ImgRgbUtils {

    /**
     * 将图片转换位像素点
     *
     * @param image
     * @return
     */
    public static Color[][] getPixels(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Color[][] pixels = new Color[h][w];
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                final Color color = new Color(image.getRGB(j, i));
                pixels[i][j] = color;
            }
        }
        return pixels;
    }

    /**
     * 将像素点转换为图片
     *
     * @param pixels
     * @return
     */
    public static BufferedImage toImage(Color[][] pixels) {
        int h = pixels.length;
        int w = pixels[0].length;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                image.setRGB(j, i, pixels[i][j].getRGB());
            }
        }
        return image;
    }

    /**
     * 通过BufferedImage图片流调整图片大小
     */
    public static BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) throws IOException {
        Image resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_AREA_AVERAGING);
        BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        outputImage.getGraphics().drawImage(resultingImage, 0, 0, null);
        return outputImage;
    }

    /**
     * 修改图片亮度
     *
     * @param colors
     * @param Light  亮度
     * @return
     */
    public static void GetGrayScaleHalf(Color[][] colors, float Light) {
        for (int x = 0; x < colors.length; x++) {
            for (int y = 0; y < colors[x].length; y++) {
                Color color = colors[x][y];
                float r = color.getRed() * Light;
                float g = color.getGreen() * Light;
                float b = color.getBlue() * Light;
                colors[x][y] = new Color((int) r, (int) g, (int) b, color.getAlpha());
            }
        }
    }
}
