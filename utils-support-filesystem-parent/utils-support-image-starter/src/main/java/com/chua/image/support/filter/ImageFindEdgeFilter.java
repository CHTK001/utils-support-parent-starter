package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 综合边缘检测图像滤镜
 *
 * 基于 Sobel 算子的双向边缘检测滤镜，同时计算水平和垂直方向的梯度，
 * 通过梯度幅值来检测图像中的边缘。相比单向 Sobel 滤镜，能够检测
 * 任意方向的边缘，提供更完整的边缘信息。
 *
 * 技术原理：
 * - 同时应用水平和垂直 Sobel 算子
 * - 计算两个方向的梯度分量 Gx 和 Gy
 * - 通过梯度幅值 |G| = √(Gx² + Gy²) 确定边缘强度
 * - 可选择性计算梯度方向 θ = arctan(Gy/Gx)
 *
 * Sobel 算子：
 * 水平方向（检测垂直边缘）：    垂直方向（检测水平边缘）：
 * [-1 -2 -1]                    [-1  0  1]
 * [ 0  0  0]                    [-2  0  2]
 * [ 1  2  1]                    [-1  0  1]
 *
 * 算法优势：
 * - 全方向边缘检测：能检测任意方向的边缘
 * - 边缘强度量化：提供边缘的强度信息
 * - 噪声抑制：Sobel 算子具有一定的平滑效果
 * - 计算效率：使用整数运算，计算速度快
 *
 * 应用场景：
 * - 图像分析：提取图像的结构信息
 * - 特征检测：为后续处理提供边缘特征
 * - 图像分割：基于边缘信息进行区域分割
 * - 目标识别：物体轮廓提取和识别
 * - 医学影像：医学图像的边缘增强和分析
 * - 工业检测：产品边缘质量检测
 *
 * @author CH
 * @version 1.0.0
 * @since 2021/6/11
 */
@Spi("FindEdge")
@SpiDescribe("综合边缘检测滤镜")
public class ImageFindEdgeFilter extends AbstractImageFilter {

    /**
     * 用于边缘检测的水平Sobel算子滤波器
     * <p>
     * 这是一个3x3的滤波器:<br>
     * -1 -2 -1 <br>
     * 0  0  0 <br>
     * 1  2  1 <br>
     */
    public static final int[] SOBEL_X = new int[]{-1, -2, -1, 0, 0, 0, 1, 2, 1};
    
    /**
     * 用于边缘检测的垂直Sobel算子滤波器
     * <p>
     * 这是一个3x3的滤波器:<br>
     * -1  0  1 <br>
     * -2  0  2 <br>
     * -1  0  1 <br>
     */
    public static final int[] SOBEL_Y = new int[]{-1, 0, 1, -2, 0, 2, -1, 0, 1};

    @Override
    /** 过滤 */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        // 计算图像总像素数
        int total = width * height;
        // 创建输出数组，存储处理后的RGB值
        byte[][] output = new byte[3][total];

        // 偏移量，计算当前行在数组中的位置
        int offset = 0;
        
        // 提取SOBEL_X滤波器的各个系数
        int x0 = SOBEL_X[0];
        int x1 = SOBEL_X[1];
        int x2 = SOBEL_X[2];
        int x3 = SOBEL_X[3];
        int x4 = SOBEL_X[4];
        int x5 = SOBEL_X[5];
        int x6 = SOBEL_X[6];
        int x7 = SOBEL_X[7];
        int x8 = SOBEL_X[8];

        // 提取SOBEL_Y滤波器的各个系数
        int k0 = SOBEL_Y[0];
        int k1 = SOBEL_Y[1];
        int k2 = SOBEL_Y[2];
        int k3 = SOBEL_Y[3];
        int k4 = SOBEL_Y[4];
        int k5 = SOBEL_Y[5];
        int k6 = SOBEL_Y[6];
        int k7 = SOBEL_Y[7];
        int k8 = SOBEL_Y[8];

        // 存储Y方向和X方向的RGB分量卷积结果
        int yr = 0, yg = 0, yb = 0;
        int xr = 0, xg = 0, xb = 0;
        // 存储最终的RGB分量值
        int r = 0, g = 0, b = 0;
        
        // 遍历图像的每个像素（跳过边界）
        for (int row = 1; row < height - 1; row++) {
            // 计算当前行的偏移量
            offset = row * width;
            for (int col = 1; col < width - 1; col++) {
                // 对红色分量进行卷积运算
                yr = k0 * (rArr[offset - width + col - 1] & 0xff) + 
                     k1 * (rArr[offset - width + col] & 0xff) + 
                     k2 * (rArr[offset - width + col + 1] & 0xff) + 
                     k3 * (rArr[offset + col - 1] & 0xff) + 
                     k4 * (rArr[offset + col] & 0xff) + 
                     k5 * (rArr[offset + col + 1] & 0xff) + 
                     k6 * (rArr[offset + width + col - 1] & 0xff) + 
                     k7 * (rArr[offset + width + col] & 0xff) + 
                     k8 * (rArr[offset + width + col + 1] & 0xff);

                xr = x0 * (rArr[offset - width + col - 1] & 0xff) + 
                     x1 * (rArr[offset - width + col] & 0xff) + 
                     x2 * (rArr[offset - width + col + 1] & 0xff) + 
                     x3 * (rArr[offset + col - 1] & 0xff) + 
                     x4 * (rArr[offset + col] & 0xff) + 
                     x5 * (rArr[offset + col + 1] & 0xff) + 
                     x6 * (rArr[offset + width + col - 1] & 0xff) + 
                     x7 * (rArr[offset + width + col] & 0xff) + 
                     x8 * (rArr[offset + width + col + 1] & 0xff);

                // 对绿色分量进行卷积运算
                yg = k0 * (gArr[offset - width + col - 1] & 0xff) + 
                     k1 * (gArr[offset - width + col] & 0xff) + 
                     k2 * (gArr[offset - width + col + 1] & 0xff) + 
                     k3 * (gArr[offset + col - 1] & 0xff) + 
                     k4 * (gArr[offset + col] & 0xff) + 
                     k5 * (gArr[offset + col + 1] & 0xff) + 
                     k6 * (gArr[offset + width + col - 1] & 0xff) + 
                     k7 * (gArr[offset + width + col] & 0xff) + 
                     k8 * (gArr[offset + width + col + 1] & 0xff);

                xg = x0 * (gArr[offset - width + col - 1] & 0xff) + 
                     x1 * (gArr[offset - width + col] & 0xff) + 
                     x2 * (gArr[offset - width + col + 1] & 0xff) + 
                     x3 * (gArr[offset + col - 1] & 0xff) + 
                     x4 * (gArr[offset + col] & 0xff) + 
                     x5 * (gArr[offset + col + 1] & 0xff) + 
                     x6 * (gArr[offset + width + col - 1] & 0xff) + 
                     x7 * (gArr[offset + width + col] & 0xff) + 
                     x8 * (gArr[offset + width + col + 1] & 0xff);
                
                // 对蓝色分量进行卷积运算
                yb = k0 * (bArr[offset - width + col - 1] & 0xff) + 
                     k1 * (bArr[offset - width + col] & 0xff) + 
                     k2 * (bArr[offset - width + col + 1] & 0xff) + 
                     k3 * (bArr[offset + col - 1] & 0xff) + 
                     k4 * (bArr[offset + col] & 0xff) + 
                     k5 * (bArr[offset + col + 1] & 0xff) + 
                     k6 * (bArr[offset + width + col - 1] & 0xff) + 
                     k7 * (bArr[offset + width + col] & 0xff) + 
                     k8 * (bArr[offset + width + col + 1] & 0xff);

                xb = x0 * (bArr[offset - width + col - 1] & 0xff) + 
                     x1 * (bArr[offset - width + col] & 0xff) + 
                     x2 * (bArr[offset - width + col + 1] & 0xff) + 
                     x3 * (bArr[offset + col - 1] & 0xff) + 
                     x4 * (bArr[offset + col] & 0xff) + 
                     x5 * (bArr[offset + col + 1] & 0xff) + 
                     x6 * (bArr[offset + width + col - 1] & 0xff) + 
                     x7 * (bArr[offset + width + col] & 0xff) + 
                     x8 * (bArr[offset + width + col + 1] & 0xff);

                // 计算梯度幅值
                r = (int) Math.sqrt(yr * yr + xr * xr);
                g = (int) Math.sqrt(yg * yg + xg * xg);
                b = (int) Math.sqrt(yb * yb + xb * xb);

                // 将结果限制在0-255范围内并存储到输出数组
                output[0][offset + col] = (byte) BufferedImageUtils.clamp(r);
                output[1][offset + col] = (byte) BufferedImageUtils.clamp(g);
                output[2][offset + col] = (byte) BufferedImageUtils.clamp(b);

                // 计算梯度角度（这部分计算在当前代码中未被使用）
                double dy = (yr + yg + yb);
                double dx = (xr + xg + xb);
                double theta = Math.atan(dy / dx);

                // 重置变量，为下一个像素做准备
                yr = 0;
                yg = 0;
                yb = 0;
                xr = 0;
                xg = 0;
                xb = 0;
            }
        }
        // 将处理后的RGB数据放回图像
        putRgb(output[0], output[1], output[2]);
        // 返回处理后的图像
        return toBitmap();
    }
}
