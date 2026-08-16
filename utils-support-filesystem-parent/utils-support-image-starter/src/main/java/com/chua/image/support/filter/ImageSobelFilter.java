package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Sobel 边缘检测图像滤镜
 *
 * 实现 Sobel 算子进行边缘检测，通过计算图像梯度来识别和突出显示图像中的边缘。
 * Sobel 算子是一种经典的边缘检测算法，在计算机视觉和图像处理中广泛应用。
 *
 * 技术原理：
 * - 使用 3x3 卷积核计算图像梯度
 * - 分别计算水平方向（X方向）和垂直方向（Y方向）的梯度
 * - 通过一阶导数近似检测边缘
 * - 对噪声具有一定的抑制能力
 *
 * Sobel 算子：
 * X方向（水平边缘检测）：
 * [-1  0  1]
 * [-2  0  2]
 * [-1  0  1]
 *
 * Y方向（垂直边缘检测）：
 * [-1 -2 -1]
 * [ 0  0  0]
 * [ 1  2  1]
 *
 * 算法特点：
 * - 计算效率高，适合实时处理
 * - 对噪声有一定的平滑作用
 * - 能够检测不同方向的边缘
 * - 边缘定位精度较好
 *
 * 应用场景：
 * - 边缘检测：识别图像中的物体轮廓
 * - 特征提取：为后续图像分析提供特征
 * - 图像分割：基于边缘信息进行区域分割
 * - 目标识别：辅助物体识别和跟踪
 * - 医学影像：医学图像的边缘增强
 * - 工业检测：产品质量检测中的边缘分析
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@SpiDescribe("Sobel边缘检测滤镜")
@Spi("sobel")
@AllArgsConstructor
@NoArgsConstructor
public class ImageSobelFilter extends AbstractImageFilter {

    /**
     * Sobel Y方向（垂直边缘检测）卷积核
     */
    public static int[] sobelY = new int[]{-1, -2, -1, 0, 0, 0, 1, 2, 1};

    /**
     * Sobel X方向（水平边缘检测）卷积核
     */
    public static int[] sobelX = new int[]{-1, 0, 1, -2, 0, 2, -1, 0, 1};

    /**
     * 是否使用X方向检测，true为X方向（检测垂直边缘），false为Y方向（检测水平边缘）
     */
    private boolean xdirect = true;

    /**
     * 执行 Sobel 边缘检测滤镜处理
     *
     * 对图像应用 Sobel 算子进行边缘检测。根据 xdirect 参数选择检测方向：
     * - true：使用 X 方向算子，检测垂直边缘
     * - false：使用 Y 方向算子，检测水平边缘
     *
     * @param src 源图像
     * @param dst 目标图像（此参数未使用）
     * @return 边缘检测后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int total = width * height;
        // RGB三个通道的输出数组
        byte[][] output = new byte[3][total];

        int offset = 0;
        // Sobel 卷积核的9个系数
        int k0 = 0, k1 = 0, k2 = 0;
        int k3 = 0, k4 = 0, k5 = 0;
        int k6 = 0, k7 = 0, k8 = 0;

        // 根据检测方向选择相应的 Sobel 算子
        if (xdirect) {
            // X方向算子：检测垂直边缘
            k0 = sobelX[0]; k1 = sobelX[1]; k2 = sobelX[2];
            k3 = sobelX[3]; k4 = sobelX[4]; k5 = sobelX[5];
            k6 = sobelX[6]; k7 = sobelX[7]; k8 = sobelX[8];
        } else {
            // Y方向算子：检测水平边缘
            k0 = sobelY[0]; k1 = sobelY[1]; k2 = sobelY[2];
            k3 = sobelY[3]; k4 = sobelY[4]; k5 = sobelY[5];
            k6 = sobelY[6]; k7 = sobelY[7]; k8 = sobelY[8];
        }

        // 梯度计算结果
        int sr = 0, sg = 0, sb = 0;
        int r = 0, g = 0, b = 0;

        // 遍历图像像素（跳过边界像素，因为需要3x3邻域）
        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {

        // 计算红色通道的 Sobel 梯度，方向顺序：左上、上、右上、左、中心、右、左下、下、右下
        sr = k0 * (rArr[offset - width + col - 1] & 0xff)
                + k1 * (rArr[offset - width + col] & 0xff)
                + k2 * (rArr[offset - width + col + 1] & 0xff)
                + k3 * (rArr[offset + col - 1] & 0xff)
                + k4 * (rArr[offset + col] & 0xff)
                + k5 * (rArr[offset + col + 1] & 0xff)
                + k6 * (rArr[offset + width + col - 1] & 0xff)
                + k7 * (rArr[offset + width + col] & 0xff)
                + k8 * (rArr[offset + width + col + 1] & 0xff);

                // 计算绿色通道的 Sobel 梯度
                sg = k0 * (gArr[offset - width + col - 1] & 0xff)
                        + k1 * (gArr[offset - width + col] & 0xff)
                        + k2 * (gArr[offset - width + col + 1] & 0xff)
                        + k3 * (gArr[offset + col - 1] & 0xff)
                        + k4 * (gArr[offset + col] & 0xff)
                        + k5 * (gArr[offset + col + 1] & 0xff)
                        + k6 * (gArr[offset + width + col - 1] & 0xff)
                        + k7 * (gArr[offset + width + col] & 0xff)
                        + k8 * (gArr[offset + width + col + 1] & 0xff);

                // 计算蓝色通道的 Sobel 梯度
                sb = k0 * (bArr[offset - width + col - 1] & 0xff)
                        + k1 * (bArr[offset - width + col] & 0xff)
                        + k2 * (bArr[offset - width + col + 1] & 0xff)
                        + k3 * (bArr[offset + col - 1] & 0xff)
                        + k4 * (bArr[offset + col] & 0xff)
                        + k5 * (bArr[offset + col + 1] & 0xff)
                        + k6 * (bArr[offset + width + col - 1] & 0xff)
                        + k7 * (bArr[offset + width + col] & 0xff)
                        + k8 * (bArr[offset + width + col + 1] & 0xff);

                // 保存梯度计算结果
                r = sr;
                g = sg;
                b = sb;

                // 将结果限制在有效范围内并存储
                output[0][offset + col] = (byte) BufferedImageUtils.clamp(r);
                output[1][offset + col] = (byte) BufferedImageUtils.clamp(g);
                output[2][offset + col] = (byte) BufferedImageUtils.clamp(b);

                // 重置梯度值，准备处理下一个像素
                sr = 0;
                sg = 0;
                sb = 0;
            }
        }

        // 将处理后的RGB数据设置回图像
        putRgb(output[0], output[1], output[2]);
        return toBitmap();
    }
}
