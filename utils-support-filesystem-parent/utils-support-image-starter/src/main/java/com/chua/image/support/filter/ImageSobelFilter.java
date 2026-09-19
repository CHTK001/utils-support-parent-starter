package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Sobel 边缘检测滤镜
 *
 * 使用 Sobel 算子对图像进行边缘检测，提取图像中的边缘特征。
 * Sobel 算子是一种离散差分算子，用来计算图像亮度的近似梯度。
 * 可以分别计算水平方向和垂直方向的边缘。
 *
 * 技术原理：
 * - 使用3x3卷积核对图像进行卷积运算
 * - 分别计算水平和垂直方向的梯度
 * - 通过梯度幅值确定边缘强度
 * - 支持单方向或双方向边缘检测
 *
 * Sobel 算子：
 * X方向（水平边缘检测）卷积核：
 * [-1  0  1]
 * [-2  0  2]
 * [-1  0  1]
 *
 * Y方向（垂直边缘检测）卷积核：
 * [-1 -2 -1]
 * [ 0  0  0]
 * [ 1  2  1]
 *
 * 算法流程：
 * - 遍历图像内部像素（排除边界）
 * - 对每个像素应用Sobel卷积核
 * - 计算RGB三通道的梯度值
 * - 将结果限制在0-255范围内
 *
 * 应用场景：
 * - 边缘检测：提取图像中的边缘特征
 * - 图像分割：基于边缘的图像分割
 * - 特征提取：计算机视觉预处理
 * - 图像增强：突出图像轮廓
 *
 * 注意事项：
 * - 边缘像素不参与计算（1像素边界）
 * - 输出为灰度边缘图
 * - 可通过xdirect参数选择检测方向
 * - 支持RGB三通道独立计算
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // X 方向边缘（默认）
 * BufferedImage edges = new ImageSobelFilter().converter(src);
 *
 * // Y 方向边缘（链式）
 * BufferedImage edges = new ImageSobelFilter().setXdirect(false).converter(src);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>xdirect</b>（默认 true）：是否使用 X 方向卷积核（垂直方向算子，检测水平边缘）。
 *       设为 false 则使用 Y 方向卷积核（水平方向算子，检测垂直边缘）。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpiDescribe("Sobel边缘检测滤镜")
@Spi("sobel")
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
@Setter
public class ImageSobelFilter extends AbstractImageFilter {

    /**
     * Sobel Y方向（垂直边缘检测）卷积核
     *
     * @see ImageProcessorUtils#SOBEL_Y
     */
    public static int[] sobelY = ImageProcessorUtils.SOBEL_Y;

    /**
     * Sobel X方向（水平边缘检测）卷积核
     *
     * @see ImageProcessorUtils#SOBEL_X
     */
    public static int[] sobelX = ImageProcessorUtils.SOBEL_X;

    /**
     * 是否检测X方向边缘，true表示水平边缘检测（垂直方向算子），
     * false表示垂直边缘检测（水平方向算子）
     */
    private boolean xdirect = true;

    /**
     * 执行 Sobel 边缘检测滤镜处理
     *
     * 使用 Sobel 算子对图像进行边缘检测，通过 xdirect 参数控制检测方向：
     * - true：X 方向卷积核（垂直方向算子）
     * - false：Y 方向卷积核（水平方向算子）
     *
     * @param src 源图像
     * @param dst 目标图像（可选，若为空则自动创建）
     * @return 边缘检测处理后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int total = width * height;
        // RGB通道数据输出数组
        byte[][] output = new byte[3][total];

        int offset = 0;
        // Sobel 卷积核系数
        int k0 = 0, k1 = 0, k2 = 0;
        int k3 = 0, k4 = 0, k5 = 0;
        int k6 = 0, k7 = 0, k8 = 0;

        // 根据方向选择使用Sobel算子
        if (xdirect) {
            // X方向卷积核（垂直方向算子）
            k0 = sobelX[0];
            k1 = sobelX[1];
            k2 = sobelX[2];
            k3 = sobelX[3];
            k4 = sobelX[4];
            k5 = sobelX[5];
            k6 = sobelX[6];
            k7 = sobelX[7];
            k8 = sobelX[8];
        } else {
            // Y方向卷积核（水平方向算子）
            k0 = sobelY[0];
            k1 = sobelY[1];
            k2 = sobelY[2];
            k3 = sobelY[3];
            k4 = sobelY[4];
            k5 = sobelY[5];
            k6 = sobelY[6];
            k7 = sobelY[7];
            k8 = sobelY[8];
        }

        // 临时变量初始化
        int sr = 0, sg = 0, sb = 0;
        int r = 0, g = 0, b = 0;

        // 遍历图像内部像素，排除边界像素，应用3x3卷积核
        for (int row = 1; row < height - 1; row++) {
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {

        // 计算红色通道的Sobel卷积结果
        sr = k0 * (rArr[offset - width + col - 1] & 0xff)
                + k1 * (rArr[offset - width + col] & 0xff)
                + k2 * (rArr[offset - width + col + 1] & 0xff)
                + k3 * (rArr[offset + col - 1] & 0xff)
                + k4 * (rArr[offset + col] & 0xff)
                + k5 * (rArr[offset + col + 1] & 0xff)
                + k6 * (rArr[offset + width + col - 1] & 0xff)
                + k7 * (rArr[offset + width + col] & 0xff)
                + k8 * (rArr[offset + width + col + 1] & 0xff);

                // 计算绿色通道的Sobel卷积结果
                sg = k0 * (gArr[offset - width + col - 1] & 0xff)
                        + k1 * (gArr[offset - width + col] & 0xff)
                        + k2 * (gArr[offset - width + col + 1] & 0xff)
                        + k3 * (gArr[offset + col - 1] & 0xff)
                        + k4 * (gArr[offset + col] & 0xff)
                        + k5 * (gArr[offset + col + 1] & 0xff)
                        + k6 * (gArr[offset + width + col - 1] & 0xff)
                        + k7 * (gArr[offset + width + col] & 0xff)
                        + k8 * (gArr[offset + width + col + 1] & 0xff);

                // 计算蓝色通道的Sobel卷积结果
                sb = k0 * (bArr[offset - width + col - 1] & 0xff)
                        + k1 * (bArr[offset - width + col] & 0xff)
                        + k2 * (bArr[offset - width + col + 1] & 0xff)
                        + k3 * (bArr[offset + col - 1] & 0xff)
                        + k4 * (bArr[offset + col] & 0xff)
                        + k5 * (bArr[offset + col + 1] & 0xff)
                        + k6 * (bArr[offset + width + col - 1] & 0xff)
                        + k7 * (bArr[offset + width + col] & 0xff)
                        + k8 * (bArr[offset + width + col + 1] & 0xff);

                // 赋值梯度结果
                r = sr;
                g = sg;
                b = sb;

                // 限制通道值在0-255范围内并写入输出数组
                output[0][offset + col] = (byte) BufferedImageUtils.clamp(r);
                output[1][offset + col] = (byte) BufferedImageUtils.clamp(g);
                output[2][offset + col] = (byte) BufferedImageUtils.clamp(b);

                // 重置临时变量为下一像素计算做准备
                sr = 0;
                sg = 0;
                sb = 0;
            }
        }

        // 将RGB数据写回图像并转换为位图
        putRgb(output[0], output[1], output[2]);
        return toBitmap();
    }
}
