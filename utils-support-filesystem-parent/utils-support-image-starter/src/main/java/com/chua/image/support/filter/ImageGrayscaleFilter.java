package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * NTSC 标准灰度转换滤镜
 *
 * 使用 NTSC（国家电视系统委员会）标准的亮度计算公式将彩色图像转换为灰度图像。
 * 考虑了人眼对不同颜色的敏感度差异，提供更自然的灰度转换效果。
 *
 * 技术原理：
 * - 基于 NTSC 标准的亮度计算公式
 * - 考虑人眼对绿色最敏感，红色次之，蓝色最不敏感
 * - 使用加权平均而非简单平均，获得更真实的灰度效果
 * - 保持 Alpha 通道不变
 *
 * NTSC 灰度公式：
 * Gray = 0.299 × R + 0.587 × G + 0.114 × B
 *
 * 实现优化：
 * - 使用整数运算提高性能：(R×77 + G×151 + B×28) >> 8
 * - 权重系数：77/256≈0.299, 151/256≈0.587, 28/256≈0.114
 * - 位移运算替代除法，提高计算效率
 *
 * 视觉效果：
 * - 自然的灰度转换，符合人眼视觉特性
 * - 保持图像的亮度层次和对比度
 * - 绿色区域在灰度图中相对较亮
 * - 蓝色区域在灰度图中相对较暗
 *
 * 应用场景：
 * - 图像预处理：为后续处理准备灰度图像
 * - 打印优化：为黑白打印准备图像
 * - 图像分析：简化图像数据，专注于亮度信息
 * - 艺术效果：创建经典的黑白照片效果
 * - 计算机视觉：为算法处理准备单通道图像
 *
 * 性能特点：
 * - 高效的整数运算实现
 * - 支持索引颜色模型
 * - 逐像素处理，适合大图像
 * - 内存占用优化
 *
 * @author CH
 * @版本 1.0.0
 * @since 2021/6/11
 */
@SpiDescribe("NTSC标准灰度转换滤镜")
@Spi("grayscale")
public class ImageGrayscaleFilter extends AbstractImagePointFilter {

    /**
     * 构造函数
     *
     * 初始化灰度滤镜，设置支持索引颜色模型处理。
     */
    public ImageGrayscaleFilter() {
        canFilterIndexColorModel = true;
    }

    /**
     * 对单个像素进行灰度转换
     *
     * 使用 NTSC 标准公式计算灰度值，保持 Alpha 通道不变。
     *
     * @param x   像素的 X 坐标（此参数未使用）
     * @param y   像素的 Y 坐标（此参数未使用）
     * @param rgb 原始 ARGB 像素值
     * @return 转换后的灰度 ARGB 像素值
     */
    @Override
    public int filterRgb(int x, int y, int rgb) {
        // 提取 Alpha 通道，保持不变
        int a = rgb & 0xff000000;

        // 提取 RGB 分量
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;

        // 使用 NTSC 标准公式计算灰度值
        // Gray = 0.299×R + 0.587×G + 0.114×B
        // 优化为整数运算：(R×77 + G×151 + B×28) >> 8
        rgb = (r * 77 + g * 151 + b * 28) >> 8;

        // 重新组合 ARGB 值，RGB 三个通道都使用相同的灰度值
        return a | (rgb << 16) | (rgb << 8) | rgb;
    }


}
