package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 二值化（黑白）滤镜
 *
 * 将彩色图像转换为纯黑白（1 位）二值图像：
 * 先用 3×3 邻域均值 + 固定阈值（130）判定每个像素的黑白，
 * 输出 TYPE_BYTE_BINARY 图像。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * BufferedImage bin = new ImageBinImageFilter().converter(src);
 * }</pre>
 *
 * <h3>效果说明</h3>
 * <ul>
 *   <li>适合印章识别、扫描文档清理、高对比度图标处理</li>
 *   <li>阈值固定为 130，无法调节；需要可调阈值时建议参考
 *       {@link com.chua.common.support.image.ImageProcessorUtils} 自行实现</li>
 *   <li>输出为 1 位黑白图，打印/扫描场景下文件体积很小</li>
 * </ul>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li>无配置参数（行为固定：邻域均值 + 阈值 130）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
 */
@Spi("Bin")
@SpiDescribe("二值化滤镜")
public class ImageBinImageFilter extends AbstractImageFilter {
    /**
    * 获取镜像rgb
    *
    * @param i i
    * @return 获取镜像rgb的结果
    */
    private static int getImageRgb(int i) {
        String argb = Integer.toHexString(i);
        int r = Integer.parseInt(argb.substring(2, 4), 16);
        int g = Integer.parseInt(argb.substring(4, 6), 16);
        int b = Integer.parseInt(argb.substring(6, 8), 16);
        return (r + g + b) / 3;
    }

    /**
    * 获取Gray
    *
    * @param gray gray
    * @param x x
    * @param y y
    * @param w w
    * @param h h
    * @return 获取gray的结果
    */
    public static int getGray(int[][] gray, int x, int y, int w, int h) {
        int rs = gray[x][y]
                + (x == 0 ? 255 : gray[x - 1][y])
                + (x == 0 || y == 0 ? 255 : gray[x - 1][y - 1])
                + (x == 0 || y == h - 1 ? 255 : gray[x - 1][y + 1])
                + (y == 0 ? 255 : gray[x][y - 1])
                + (y == h - 1 ? 255 : gray[x][y + 1])
                + (x == w - 1 ? 255 : gray[x + 1][y])
                + (x == w - 1 || y == 0 ? 255 : gray[x + 1][y - 1])
                + (x == w - 1 || y == h - 1 ? 255 : gray[x + 1][y + 1]);
        return rs / 9;
    }

    @Override
    /** 过滤 */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int h = src.getHeight();
        int w = src.getWidth();
        int rgb = src.getRGB(0, 0);
        int[][] arr = new int[w][h];

        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                arr[i][j] = getImageRgb(src.getRGB(i, j));
            }

        }

        BufferedImage bufferedImage = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        int fz = 130;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (getGray(arr, i, j, w, h) > fz) {
                    int black = new Color(255, 255, 255).getRGB();
                    bufferedImage.setRGB(i, j, black);
                } else {
                    int white = new Color(0, 0, 0).getRGB();
                    bufferedImage.setRGB(i, j, white);
                }
            }
        }

        return bufferedImage;
    }
}
