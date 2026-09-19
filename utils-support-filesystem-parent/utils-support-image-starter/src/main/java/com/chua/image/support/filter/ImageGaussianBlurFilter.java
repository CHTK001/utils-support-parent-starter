package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 高斯模糊图像滤镜
 *
 * 实现高斯模糊效果的图像滤镜，通过应用高斯核函数对图像进行卷积运算，
 * 产生平滑的模糊效果。支持多线程并行处理以提高性能。
 *
 * 技术原理：
 * - 基于高斯分布函数生成卷积核
 * - 分别进行水平和垂直方向的一维卷积
 * - 使用可分离卷积提高计算效率
 * - 多线程并行处理RGB三个颜色通道
 *
 * 算法特点：
 * - 可调节模糊强度（sigma参数）
 * - 自适应核大小计算
 * - 边界像素处理
 * - 内存优化的实现方式
 *
 * 应用场景：
 * - 图像降噪：去除图像中的高频噪声
 * - 艺术效果：创建柔和、梦幻的视觉效果
 * - 背景虚化：突出主体，模糊背景
 * - 图像预处理：为后续处理准备平滑的图像
 * - 缩略图生成：减少细节以适应小尺寸显示
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认 sigma=2.0（中等模糊）
 * BufferedImage blurred = new ImageGaussianBlurFilter().converter(src);
 *
 * // 构造器指定卷积核 + sigma
 * BufferedImage blurred = new ImageGaussianBlurFilter(kernel, 1.5).converter(src);
 *
 * // 链式调整 sigma（更大 = 更模糊）
 * BufferedImage blurred = new ImageGaussianBlurFilter()
 *         .setSigma(4.0)
 *         .converter(src);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>kernel</b>（默认空数组，运行时自动生成）：高斯卷积核。
 *       一般无需手动设置，由 sigma 通过 {@link #makeGaussianKernel(double, double, int)} 自动计算。</li>
 *   <li><b>sigma</b>（默认 2.0）：高斯分布标准差，控制模糊程度。
 *       越大越模糊；0.5-1.0 轻微模糊，2.0-4.0 中等，4.0+ 强模糊。</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>内部使用线程池并行处理 3 个颜色通道（CPU 密集型任务）</li>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>继承自本类的 {@link ImageUsmFilterImage} 用于 USM 锐化</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@SpiDescribe("高斯模糊滤镜")
@Spi("gaussianBlur")
@NoArgsConstructor
@Accessors(chain = true)
@Setter
public class ImageGaussianBlurFilter extends AbstractImageFilter {

    /**
     * 高斯卷积核数组
     */
    private float[] kernel = new float[0];

    /**
     * 高斯分布的标准差，控制模糊程度
     */
    private double sigma = 2;

    /** 线程池执行器 */
    ExecutorService mExecutor;

    /** 完成服务，管理并发任务 */
    CompletionService<Void> service;

    /**
     * 构造函数，使用自定义的卷积核和标准差
     *
     * @param kernel 高斯卷积核数组
     * @param sigma  高斯分布的标准差
     */
    public ImageGaussianBlurFilter(float[] kernel, double sigma) {
        this.kernel = kernel;
        this.sigma = sigma;
    }

    /**
     * 执行一维高斯模糊卷积
     *
     * 对图像的一个颜色通道进行一维高斯卷积运算。通过分离的水平垂直卷积
     * 来实现二维高斯模糊，这种方法比直接二维卷积更高效。
     *
     * @param inPixels  输入像素数据数组
     * @param outPixels 输出像素数据数组
     * @param width     图像宽度
     * @param height    图像高度
     */
    private void blur(byte[] inPixels, byte[] outPixels, int width, int height) {
        int subCol = 0;
        int index = 0, index2 = 0;
        float sum = 0;
        int k = kernel.length - 1;

        // 逐行处理图像
        for (int row = 0; row < height; row++) {
            int c = 0;
            index = row;

            // 逐列处理像素
            for (int col = 0; col < width; col++) {
                sum = 0;

                // 应用高斯卷积核
                for (int m = -k; m < kernel.length; m++) {
                    subCol = col + m;

                    // 边界处理：超出边界时使用边界像素值
                    if (subCol < 0 || subCol >= width) {
                        subCol = 0;
                    }

                    index2 = row * width + subCol;
                    c = inPixels[index2] & 0xff;
                    sum += c * kernel[Math.abs(m)];
                }

                // 限制结果在有效范围内并存储
                outPixels[index] = (byte) BufferedImageUtils.clamp(sum);
                index += height;
            }
        }
    }

    /**
     * 执行高斯模糊滤镜处理
     *
     * 对输入图像应用高斯模糊效果。使用多线程并行处理RGB三个颜色通道，
     * 先进行水平方向的模糊，再进行垂直方向的模糊，实现完整的二维高斯模糊。
     *
     * @param src 源图像
     * @param dst 目标图像（此参数未使用）
     * @return 应用高斯模糊后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        final int size = width * height;
        // RGB三个颜色通道
        // = 3;
        int dims = 3;

        // 生成高斯卷积核
        makeGaussianKernel(sigma, 0.002, Math.min(width, height));

        // 创建线程池进行并行处理
        mExecutor = ThreadUtils.newFixedThreadExecutor(dims, "gaussian-blur-task");
        service = new ExecutorCompletionService<>(mExecutor);

        // 为每个颜色通道提交处理任务
        for (int i = 0; i < dims; i++) {
            final int channelIndex = i;
            service.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    byte[] inPixels = toColorByte(channelIndex);
                    byte[] tempPixels = new byte[size];

                    // 先进行水平方向的高斯模糊
                    blur(inPixels, tempPixels, width, height);

                    // 再进行垂直方向的高斯模糊
                    blur(tempPixels, inPixels, height, width);

                    return null;
                }
            });
        }

        // 等待所有任务完成
        for (int i = 0; i < dims; i++) {
            try {
                service.take();
            } catch (InterruptedException e) {
                log.error("高斯模糊处理线程被中断", e);
                Thread.currentThread().interrupt();
            }
        }

        // 关闭线程池
        mExecutor.shutdown();

 // 将处理后的RGB数据转换为缓冲镜像
        return toBitmap();
    }


    /**
     * 生成高斯卷积核
     *
     * 根据给定的标准差和精度要求生成一维高斯卷积核。
     * 卷积核的大小会根据标准差自动计算，确保在指定精度下的高斯分布近似。
     *
     * @param sigma     高斯分布的标准差，控制模糊程度
     * @param accuracy  精度要求，确定卷积核的截断点
     * @param maxRadius 最大卷积核半径，防止卷积核过大
     */
    public void makeGaussianKernel(final double sigma, final double accuracy, int maxRadius) {
        // 根据精度要求计算卷积核半径
        int kRadius = (int) Math.ceil(sigma * Math.sqrt(-2 * Math.log(accuracy))) + 1;

        // 确保最大半径不小于50
        if (maxRadius < 50) {
            maxRadius = 50;
        }

        // 限制卷积核大小
        if (kRadius > maxRadius) {
            kRadius = maxRadius;
        }

        // 创建卷积核数组
        kernel = new float[kRadius];

        // 计算高斯函数值
        for (int i = 0; i < kRadius; i++) {
            kernel[i] = (float) (Math.exp(-0.5 * i * i / sigma / sigma));
        }

        // 计算归一化因子
        double sum;
        if (kRadius < maxRadius) {
            // 精确计算归一化因子
            sum = kernel[0];
            for (int i = 1; i < kRadius; i++) {
                // 对称性，每个非零项计算两次
                // * kernel[i];
                sum += 2 * kernel[i];
            }
        } else {
            // 使用理论值作为归一化因子
            sum = sigma * Math.sqrt(2 * Math.PI);
        }

        // 归一化卷积核，确保所有权重之和为1
        for (int i = 0; i < kRadius; i++) {
            kernel[i] = (float) (kernel[i] / sum);
        }
    }
}
