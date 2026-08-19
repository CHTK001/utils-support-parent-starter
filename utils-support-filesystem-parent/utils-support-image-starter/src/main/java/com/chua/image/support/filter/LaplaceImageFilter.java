package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 拉普拉斯图像锐化滤镜
 *
 * 基于拉普拉斯算子的图像锐化滤镜，提供多种图像增强处理模式。
 * 拉普拉斯算子是一种二阶微分算子，能够检测图像中的边缘并进行锐化处理。
 *
 * 技术原理：
 * - 拉普拉斯算子：二阶微分算子，对图像进行边缘检测
 * - 图像锐化：通过增强边缘来提高图像清晰度
 * - 多算法融合：结合拉普拉斯、Sobel、均值滤波等多种算法
 * - 伽马校正：调整图像的亮度和对比度
 *
 * 拉普拉斯算子（3x3）：
 * [ 0 -1  0]
 * [-1  4 -1]
 * [ 0 -1  0]
 *
 * 处理模式：
 * 1. 基础拉普拉斯处理：直接应用拉普拉斯算子
 * 2. 拉普拉斯叠加处理：拉普拉斯结果与原图叠加
 * 3. Sobel边缘检测：使用Sobel算子进行边缘检测
 * 4. 均值滤波处理：5x5均值滤波平滑处理
 * 5. 数学运算处理：多种算法结果的数学组合
 * 6. 伽马校正处理：最终的亮度和对比度调整
 *
 * 算法特点：
 * - 边缘增强：有效增强图像的边缘和细节
 * - 噪声敏感：对噪声比较敏感，可能放大噪声
 * - 多级处理：提供从简单到复杂的多种处理级别
 * - 自适应处理：可根据图像特点选择合适的处理模式
 *
 * 应用场景：
 * - 图像锐化：提高图像的清晰度和细节
 * - 边缘增强：突出显示图像中的边缘信息
 * - 医学影像：医学图像的边缘增强和细节提升
 * - 工业检测：产品表面缺陷检测和边缘分析
 * - 图像预处理：为后续处理准备高质量图像
 * - 印刷出版：提高印刷图像的清晰度
 *
 * 使用建议：
 * - 对于噪声较多的图像，建议先进行降噪处理
 * - 可根据图像特点选择合适的处理强度
 * - 建议与其他滤镜组合使用以获得最佳效果
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/10/2
 */
@Spi("laplace")
@SpiDescribe("拉普拉斯图像锐化滤镜")
public class LaplaceImageFilter extends AbstractImageFilter{
    /**
     * 执行拉普拉斯滤镜处理
     *
     * 默认使用拉普拉斯叠加处理模式，将拉普拉斯算子的结果与原图像叠加，
     * 实现图像锐化效果。
     *
     * @param src 源图像
     * @param dst 目标图像（此参数未使用）
     * @return 处理后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return laplaceAddProcess(src);
    
    }

    /**
     * 基础拉普拉斯处理
     *
     * 直接应用拉普拉斯算子对图像进行边缘检测和锐化处理。
     * 使用标准的3x3拉普拉斯卷积核进行处理。
     *
     * @param src 源图像
     * @return 拉普拉斯处理后的图像
     */
    public BufferedImage laplaceProcess(BufferedImage src) {

        // 拉普拉斯算子
        int[] LAPLACE = new int[] { 0, -1, 0, -1, 4, -1, 0, -1, 0 };

        int width = src.getWidth();
        int height = src.getHeight();

        int[] pixels = new int[width * height];
        int[] outPixels = new int[width * height];

        int type = src.getType();
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            src.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        src.getRGB(0, 0, width, height, pixels, 0, width);

        int k0 = 0, k1 = 0, k2 = 0;
        int k3 = 0, k4 = 0, k5 = 0;
        int k6 = 0, k7 = 0, k8 = 0;

        k0 = LAPLACE[0];
        k1 = LAPLACE[1];
        k2 = LAPLACE[2];
        k3 = LAPLACE[3];
        k4 = LAPLACE[4];
        k5 = LAPLACE[5];
        k6 = LAPLACE[6];
        k7 = LAPLACE[7];
        k8 = LAPLACE[8];
        int offset = 0;

        int sr = 0, sg = 0, sb = 0;
        int r = 0, g = 0, b = 0;
        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {
                // red
                sr = k0 * ((pixels[offset - width + col - 1] >> 16) & 0xff)
                        + k1 * ((pixels[offset - width + col] >> 16) & 0xff)
                        + k2
                        * ((pixels[offset - width + col + 1] >> 16) & 0xff)
                        + k3 * ((pixels[offset + col - 1] >> 16) & 0xff) + k4
                        * ((pixels[offset + col] >> 16) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 16) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 16) & 0xff)
                        + k7 * ((pixels[offset + width + col] >> 16) & 0xff)
                        + k8
                        * ((pixels[offset + width + col + 1] >> 16) & 0xff);
                // green
                sg = k0 * ((pixels[offset - width + col - 1] >> 8) & 0xff) + k1
                        * ((pixels[offset - width + col] >> 8) & 0xff) + k2
                        * ((pixels[offset - width + col + 1] >> 8) & 0xff) + k3
                        * ((pixels[offset + col - 1] >> 8) & 0xff) + k4
                        * ((pixels[offset + col] >> 8) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 8) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 8) & 0xff) + k7
                        * ((pixels[offset + width + col] >> 8) & 0xff) + k8
                        * ((pixels[offset + width + col + 1] >> 8) & 0xff);
                // blue
                sb = k0 * (pixels[offset - width + col - 1] & 0xff) + k1
                        * (pixels[offset - width + col] & 0xff) + k2
                        * (pixels[offset - width + col + 1] & 0xff) + k3
                        * (pixels[offset + col - 1] & 0xff) + k4
                        * (pixels[offset + col] & 0xff) + k5
                        * (pixels[offset + col + 1] & 0xff) + k6
                        * (pixels[offset + width + col - 1] & 0xff) + k7
                        * (pixels[offset + width + col] & 0xff) + k8
                        * (pixels[offset + width + col + 1] & 0xff);
                r = sr;
                g = sg;
                b = sb;
                outPixels[offset + col] = (0xff << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | clamp(b);
                sr = 0;
                sg = 0;
                sb = 0;
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }

        return dest;
    }
    /**
     * 拉普拉斯叠加原图像处理
     *
     * 将拉普拉斯算子的处理结果与原图像进行叠加，实现图像锐化效果。
     * 这种方法能够在保持原图像信息的同时增强边缘和细节。
     *
     * 处理流程：
     * 1. 对图像应用拉普拉斯算子
     * 2. 将拉普拉斯结果与原图像像素值相加
     * 3. 限制结果在有效颜色范围内
     *
     * @param src 源图像
     * @return 拉普拉斯叠加处理后的图像
     */
    public BufferedImage laplaceAddProcess(BufferedImage src) {

        // 拉普拉斯算子
        int[] LAPLACE = new int[] { 0, -1, 0, -1, 4, -1, 0, -1, 0 };

        int width = src.getWidth();
        int height = src.getHeight();

        int[] pixels = new int[width * height];
        int[] outPixels = new int[width * height];

        int type = src.getType();
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            src.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        src.getRGB(0, 0, width, height, pixels, 0, width);

        int k0 = 0, k1 = 0, k2 = 0;
        int k3 = 0, k4 = 0, k5 = 0;
        int k6 = 0, k7 = 0, k8 = 0;

        k0 = LAPLACE[0];
        k1 = LAPLACE[1];
        k2 = LAPLACE[2];
        k3 = LAPLACE[3];
        k4 = LAPLACE[4];
        k5 = LAPLACE[5];
        k6 = LAPLACE[6];
        k7 = LAPLACE[7];
        k8 = LAPLACE[8];
        int offset = 0;

        int sr = 0, sg = 0, sb = 0;
        int r = 0, g = 0, b = 0;
        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {

                r = (pixels[offset + col] >> 16) & 0xff;
                g = (pixels[offset + col] >> 8) & 0xff;
                b = (pixels[offset + col]) & 0xff;
                // red
                sr = k0 * ((pixels[offset - width + col - 1] >> 16) & 0xff)
                        + k1 * ((pixels[offset - width + col] >> 16) & 0xff)
                        + k2
                        * ((pixels[offset - width + col + 1] >> 16) & 0xff)
                        + k3 * ((pixels[offset + col - 1] >> 16) & 0xff) + k4
                        * ((pixels[offset + col] >> 16) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 16) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 16) & 0xff)
                        + k7 * ((pixels[offset + width + col] >> 16) & 0xff)
                        + k8
                        * ((pixels[offset + width + col + 1] >> 16) & 0xff);
                // green
                sg = k0 * ((pixels[offset - width + col - 1] >> 8) & 0xff) + k1
                        * ((pixels[offset - width + col] >> 8) & 0xff) + k2
                        * ((pixels[offset - width + col + 1] >> 8) & 0xff) + k3
                        * ((pixels[offset + col - 1] >> 8) & 0xff) + k4
                        * ((pixels[offset + col] >> 8) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 8) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 8) & 0xff) + k7
                        * ((pixels[offset + width + col] >> 8) & 0xff) + k8
                        * ((pixels[offset + width + col + 1] >> 8) & 0xff);
                // blue
                sb = k0 * (pixels[offset - width + col - 1] & 0xff) + k1
                        * (pixels[offset - width + col] & 0xff) + k2
                        * (pixels[offset - width + col + 1] & 0xff) + k3
                        * (pixels[offset + col - 1] & 0xff) + k4
                        * (pixels[offset + col] & 0xff) + k5
                        * (pixels[offset + col + 1] & 0xff) + k6
                        * (pixels[offset + width + col - 1] & 0xff) + k7
                        * (pixels[offset + width + col] & 0xff) + k8
                        * (pixels[offset + width + col + 1] & 0xff);
                // 运算后的像素值和原图像素叠加
                r += sr;
                g += sg;
                b += sb;
                outPixels[offset + col] = (0xff << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | clamp(b);

                // next pixel
                r = 0;
                g = 0;
                b = 0;
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }
        return dest;
    }
    /** Sobel处理 */
    public BufferedImage sobelProcess(BufferedImage src) {

        // Sobel算子
        int[] sobel_y = new int[] { -1, -2, -1, 0, 0, 0, 1, 2, 1 };
        int[] sobel_x = new int[] { -1, 0, 1, -2, 0, 2, -1, 0, 1 };

        int width = src.getWidth();
        int height = src.getHeight();

        int[] pixels = new int[width * height];
        int[] outPixels = new int[width * height];

        int type = src.getType();
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            src.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        src.getRGB(0, 0, width, height, pixels, 0, width);

        int offset = 0;
        int x0 = sobel_x[0];
        int x1 = sobel_x[1];
        int x2 = sobel_x[2];
        int x3 = sobel_x[3];
        int x4 = sobel_x[4];
        int x5 = sobel_x[5];
        int x6 = sobel_x[6];
        int x7 = sobel_x[7];
        int x8 = sobel_x[8];

        int k0 = sobel_y[0];
        int k1 = sobel_y[1];
        int k2 = sobel_y[2];
        int k3 = sobel_y[3];
        int k4 = sobel_y[4];
        int k5 = sobel_y[5];
        int k6 = sobel_y[6];
        int k7 = sobel_y[7];
        int k8 = sobel_y[8];

        int yr = 0, yg = 0, yb = 0;
        int xr = 0, xg = 0, xb = 0;
        int r = 0, g = 0, b = 0;

        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {

                // red
                yr = k0 * ((pixels[offset - width + col - 1] >> 16) & 0xff)
                        + k1 * ((pixels[offset - width + col] >> 16) & 0xff)
                        + k2
                        * ((pixels[offset - width + col + 1] >> 16) & 0xff)
                        + k3 * ((pixels[offset + col - 1] >> 16) & 0xff) + k4
                        * ((pixels[offset + col] >> 16) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 16) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 16) & 0xff)
                        + k7 * ((pixels[offset + width + col] >> 16) & 0xff)
                        + k8
                        * ((pixels[offset + width + col + 1] >> 16) & 0xff);

                xr = x0 * ((pixels[offset - width + col - 1] >> 16) & 0xff)
                        + x1 * ((pixels[offset - width + col] >> 16) & 0xff)
                        + x2
                        * ((pixels[offset - width + col + 1] >> 16) & 0xff)
                        + x3 * ((pixels[offset + col - 1] >> 16) & 0xff) + x4
                        * ((pixels[offset + col] >> 16) & 0xff) + x5
                        * ((pixels[offset + col + 1] >> 16) & 0xff) + x6
                        * ((pixels[offset + width + col - 1] >> 16) & 0xff)
                        + x7 * ((pixels[offset + width + col] >> 16) & 0xff)
                        + x8
                        * ((pixels[offset + width + col + 1] >> 16) & 0xff);

                // green
                yg = k0 * ((pixels[offset - width + col - 1] >> 8) & 0xff) + k1
                        * ((pixels[offset - width + col] >> 8) & 0xff) + k2
                        * ((pixels[offset - width + col + 1] >> 8) & 0xff) + k3
                        * ((pixels[offset + col - 1] >> 8) & 0xff) + k4
                        * ((pixels[offset + col] >> 8) & 0xff) + k5
                        * ((pixels[offset + col + 1] >> 8) & 0xff) + k6
                        * ((pixels[offset + width + col - 1] >> 8) & 0xff) + k7
                        * ((pixels[offset + width + col] >> 8) & 0xff) + k8
                        * ((pixels[offset + width + col + 1] >> 8) & 0xff);

                xg = x0 * ((pixels[offset - width + col - 1] >> 8) & 0xff) + x1
                        * ((pixels[offset - width + col] >> 8) & 0xff) + x2
                        * ((pixels[offset - width + col + 1] >> 8) & 0xff) + x3
                        * ((pixels[offset + col - 1] >> 8) & 0xff) + x4
                        * ((pixels[offset + col] >> 8) & 0xff) + x5
                        * ((pixels[offset + col + 1] >> 8) & 0xff) + x6
                        * ((pixels[offset + width + col - 1] >> 8) & 0xff) + x7
                        * ((pixels[offset + width + col] >> 8) & 0xff) + x8
                        * ((pixels[offset + width + col + 1] >> 8) & 0xff);
                // blue
                yb = k0 * (pixels[offset - width + col - 1] & 0xff) + k1
                        * (pixels[offset - width + col] & 0xff) + k2
                        * (pixels[offset - width + col + 1] & 0xff) + k3
                        * (pixels[offset + col - 1] & 0xff) + k4
                        * (pixels[offset + col] & 0xff) + k5
                        * (pixels[offset + col + 1] & 0xff) + k6
                        * (pixels[offset + width + col - 1] & 0xff) + k7
                        * (pixels[offset + width + col] & 0xff) + k8
                        * (pixels[offset + width + col + 1] & 0xff);

                xb = x0 * (pixels[offset - width + col - 1] & 0xff) + x1
                        * (pixels[offset - width + col] & 0xff) + x2
                        * (pixels[offset - width + col + 1] & 0xff) + x3
                        * (pixels[offset + col - 1] & 0xff) + x4
                        * (pixels[offset + col] & 0xff) + x5
                        * (pixels[offset + col + 1] & 0xff) + x6
                        * (pixels[offset + width + col - 1] & 0xff) + x7
                        * (pixels[offset + width + col] & 0xff) + x8
                        * (pixels[offset + width + col + 1] & 0xff);

                // 索贝尔梯度
                r = (int) Math.sqrt(yr * yr + xr * xr);
                g = (int) Math.sqrt(yg * yg + xg * xg);
                b = (int) Math.sqrt(yb * yb + xb * xb);

                outPixels[offset + col] = (0xff << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | clamp(b);
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }
        return dest;

    }
    /**
     * 均值滤波 *
     */
    public BufferedImage meanValueProcess(BufferedImage src) {

        // 已经索贝尔处理的图像
        BufferedImage image = this.sobelProcess(src);

        int width = image.getWidth();
        int height = image.getHeight();

        int[] pixels = new int[width * height];
        int[] outPixels = new int[width * height];

        int type = image.getType();
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            image.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        image.getRGB(0, 0, width, height, pixels, 0, width);

        // 均值滤波使用的卷积模板半径，这里使用5*5均值，所以半径使用2
        int radius = 2;
        int total = (2 * radius + 1) * (2 * radius + 1);

        int r = 0, g = 0, b = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int sum = 0;
                for (int i = -radius; i <= radius; i++) {
                    int roffset = row + i;
                    roffset = (roffset < 0) ? 0
                            : (roffset >= height ? height - 1 : roffset);

                    for (int j = -radius; j <= radius; j++) {

                        int coffset = col + j;
                        coffset = (coffset < 0) ? 0
                                : (coffset >= width ? width - 1 : coffset);

                        int pixel = pixels[roffset * width + coffset];

                        r = (pixel >> 16) & 0XFF;

                        sum += r;
                    }
                }

                r = sum / total;
                g = sum / total;
                b = sum / total;

                outPixels[row * width + col] = (255 << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | clamp(b);
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }

        return dest;
    }
    /**
     * 数学运算
     */
    public BufferedImage mathProcess(BufferedImage src) {

        // 获取经拉普拉斯运算后与原图叠加的图片
        BufferedImage lapsImage = this.laplaceAddProcess(src);

        // 获取索贝尔5*5均值滤波后的图像
        BufferedImage meanImage = this.meanValueProcess(src);

        int type = src.getType();
        int width = src.getWidth();
        int height = src.getHeight();

        // 原始图像的像素信息
        int[] pixels = new int[width * height];
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            src.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        src.getRGB(0, 0, width, height, pixels, 0, width);

        // 拉普拉斯锐化后的像素信息
        int[] lapsPixels = new int[width * height];
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            lapsImage.getRaster().getDataElements(0, 0, width, height,
                    lapsPixels);
        }
        lapsImage.getRGB(0, 0, width, height, lapsPixels, 0, width);

        // Sobel和均值滤波后的像素信息
        int[] meanPixels = new int[width * height];
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            meanImage.getRaster().getDataElements(0, 0, width, height,
                    meanPixels);
        }
        meanImage.getRGB(0, 0, width, height, meanPixels, 0, width);

        int[] outPixels = new int[width * height];

        // 图像相乘
        int lr = 0, lg = 0, lb = 0;
        int mr = 0, mg = 0, mb = 0;
        int or = 0, og = 0, ob = 0;
        int r = 0, g = 0, b = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int lpixel = lapsPixels[row * width + col];
                int mpixel = meanPixels[row * width + col];

                // 原始图像
                int opixel = pixels[row * width + col];

                lr = (lpixel >> 16) & 0XFF;
                mr = (mpixel >> 16) & 0XFF;
                or = (opixel >> 16) & 0XFF;

                lg = (lpixel >> 8) & 0XFF;
                mg = (mpixel >> 8) & 0XFF;
                og = (opixel >> 8) & 0XFF;

                lb = (lpixel) & 0XFF;
                mb = (mpixel) & 0XFF;
                ob = (opixel) & 0XFF;

                /** 图像相乘 标定到0~255 */
                r = (lr * mr) / 255;
                g = (lg * mg) / 255;
                b = (lb * mb) / 255;

                // 相乘后图像与原图相加
                r = r + or;
                g = g + og;
                b = b + ob;

                outPixels[row * width + col] = (255 << 24) | (clamp(r) << 16)
                        | (clamp(g) << 8) | (clamp(b));
            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);

        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }


        return dest;
    }

    /** Clamp */
    private int clamp(int value) {
        return value > 255 ? 255 : (value < 0 ? 0 : value);
    }
    /**
     * 伽马变化
     */
    public BufferedImage gammaProcess(BufferedImage src) {

        BufferedImage image = this.mathProcess(src);

        // 伽马值 (gamma) = 0.5; // 幂级数
        double gamma = 0.5;

        int type = image.getType();
        int width = src.getWidth();
        int height = src.getHeight();

        // 经过数学变换后的像素信息
        int[] pixels = new int[width * height];
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            image.getRaster().getDataElements(0, 0, width, height, pixels);
        }
        image.getRGB(0, 0, width, height, pixels, 0, width);

        int[] outPixels = new int[width * height];

        // 建立LUT查找表
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {

            float f = (float) (i / 255.0);
            f = (float) Math.pow(f, gamma);

            lut[i] = (int) (f * 255.0);
        }

        int r = 0, g = 0, b = 0;
        int or = 0, og = 0, ob = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {

                int pixel = pixels[row * width + col];

                r = (pixel >> 16) & 0XFF;
                g = (pixel >> 8) & 0XFF;
                b = (pixel) & 0XFF;

                or = lut[r];
                og = lut[g];
                ob = lut[b];

                outPixels[row * width + col] = (255 << 24) | (clamp(or) << 16)
                        | (clamp(og) << 8) | (clamp(ob));

            }
        }

        BufferedImage dest = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        if (type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_RGB) {
            dest.getRaster().setDataElements(0, 0, width, height, outPixels);
        } else {
            dest.setRGB(0, 0, width, height, outPixels, 0, width);
        }

        return dest;
    }
}

