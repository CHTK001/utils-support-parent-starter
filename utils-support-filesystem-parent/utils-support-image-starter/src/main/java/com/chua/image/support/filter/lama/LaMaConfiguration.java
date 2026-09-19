package com.chua.image.support.filter.lama;

import lombok.Data;
import lombok.experimental.Accessors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * lama 图像修复模型配置类
 * <p>
 * lama (Large Mask Inpainting) 是一个用于图像修复的深度学习模型，
 * 可以智能地填补图像中的缺失区域或移除不需要的对象。
 * </p>
 *
 * <h3>使用要求</h3>
 * <p>
 * 使用此配置需要添加 ONNX Runtime 依赖：
 * <pre>
 * &lt;dependency&gt;
 *     &lt;groupId&gt;com.microsoft.onnxruntime&lt;/groupId&gt;
 *     &lt;artifactId&gt;onnxruntime&lt;/artifactId&gt;
 *     &lt;version&gt;1.17.1&lt;/version&gt;
 * &lt;/dependency&gt;
 * </pre>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 工厂方法创建（推荐）
 * LaMaConfiguration cfg = LaMaConfiguration.createDefault("model/lama.onnx");
 * LaMaConfiguration high = LaMaConfiguration.createHighQuality("model/lama.onnx");
 * LaMaConfiguration fast = LaMaConfiguration.createFast("model/lama.onnx");
 * LaMaConfiguration gpu  = LaMaConfiguration.createGpu("model/lama.onnx");
 *
 * // 自动 mask（按颜色检测区域）
 * LaMaConfiguration mask = LaMaConfiguration.createAutoMask(
 *         "model/lama.onnx", new int[]{255, 255, 255});  // 移除白色区域
 *
 * // 链式微调
 * LaMaConfiguration cfg = LaMaConfiguration.createDefault("model/lama.onnx")
 *         .setInputSize(1024)
 *         .setUseGpu(true);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>modelPath</b>（必填）：ONNX 模型文件路径，支持本地文件路径或资源路径</li>
 *   <li><b>inputSize</b>（默认 512）：模型输入尺寸（平方），越大效果越好但越耗内存</li>
 *   <li><b>threads</b>（默认 4）：ONNX 推理线程数</li>
 *   <li><b>useGpu</b>（默认 false）：是否启用 GPU 加速（需 GPU 版 ONNX Runtime）</li>
 *   <li><b>gpuDeviceId</b>（默认 0）：GPU 设备标识</li>
 *   <li><b>meanValues / stdValues</b>：输入图像标准化参数（RGB 三通道）</li>
 *   <li><b>maskThreshold</b>（默认 0.5）：mask 二值化阈值</li>
 *   <li><b>useAlphaAsMask</b>（默认 false）：是否用 Alpha 通道作为修复 mask</li>
 *   <li><b>autoGenerateMask</b>（默认 false）：是否按 targetColor 自动检测修复区域</li>
 *   <li><b>colorTolerance</b>（默认 30）：自动 mask 的颜色容差，值越大检测范围越宽</li>
 *   <li><b>targetColor</b>（默认白色）：自动 mask 的目标颜色（RGB）</li>
 *   <li><b>outputQuality</b>（默认 0.95）：输出图像质量，仅对 JPEG 有效</li>
 *   <li><b>keepOriginalSize</b>（默认 true）：是否输出时恢复原始尺寸</li>
 *   <li><b>featherRadius</b>（默认 2）：修复区域边缘羽化半径</li>
 *   <li><b>enablePostProcessing</b>（默认 true）：是否启用后处理（边缘平滑、颜色校正）</li>
 *   <li><b>cpuThreads</b>（默认 4）：GPU 模式下使用的 CPU 线程数</li>
 * </ul>
 *
 * <h3>验证</h3>
 * <p>调用 {@link #validate()} 可校验配置合法性（modelPath 非空、数值范围等），
 * 建议在构造 {@link LaMaOnnxInfer} 前调用。</p>
 *
 * @author CH
 * @since 2024/7/29
 */
@Data
@Accessors(chain = true)
public class LaMaConfiguration {

    /**
     * ONNX模型文件路径
     * 支持本地文件路径或资源路径
     */
    private String modelPath;

    /**
     * 输入图像尺寸
     * lama模型通常使用512x512的输入尺寸
     * 较大的尺寸可能提供更好的效果但需要更多内存
     */
    private int inputSize = 512;

    /**
     * 推理线程数
     * 控制ONNX Runtime使用的线程数量
     */
    private int threads = 4;

    /**
     * 是否使用GPU加速
     * 需要安装GPU版本的ONNX Runtime
     */
    private boolean useGpu = false;

    /**
     * GPU设备标识
     * 当使用GPU时指定设备标识，默认为0
     */
    private int gpuDeviceId = 0;

    /**
     * 输入图像的均值
     * 用于图像标准化，RGB三个通道的均值
     */
    private float[] meanValues = {0.485f, 0.456f, 0.406f};

    /**
     * 输入图像的标准差
     * 用于图像标准化，RGB三个通道的标准差
     */
    private float[] stdValues = {0.229f, 0.224f, 0.225f};

    /**
     * mask阈值
     * 用于二值化mask，像素值大于此阈值的区域将被视为需要修复的区域
     */
    private float maskThreshold = 0.5f;

    /**
     * 是否使用alpha通道作为mask
     * 如果为true，将使用输入图像的alpha通道作为修复mask
     */
    private boolean useAlphaAsMask = false;

    /**
     * 是否自动生成mask
     * 如果为true且没有提供mask，将尝试自动检测需要修复的区域
     */
    private boolean autoGenerateMask = false;

    /**
     * 自动mask生成的颜色容差
     * 用于自动检测需要修复的区域，值越大检测范围越宽
     */
    private int colorTolerance = 30;

    /**
     * 目标颜色（自动mask生成）
     * 指定需要移除的颜色，格式为RGB，默认白色 {255, 255, 255}
     */
    private int[] targetColor = {255, 255, 255};

    /**
     * 输出图像质量
     * 范围0.0-1.0，仅对JPEG格式有效
     */
    private float outputQuality = 0.95f;

    /**
     * 是否保持原始图像尺寸
     * 如果为true，输出图像将调整回原始尺寸
     */
    private boolean keepOriginalSize = true;

    /**
     * 边缘羽化半径
     * 用于平滑修复区域的边缘，减少明显的修复痕迹
     */
    private int featherRadius = 2;

    /**
     * 是否启用后处理优化
     * 包括边缘平滑、颜色校正等
     */
    private boolean enablePostProcessing = true;

    /**
     * CPU线程数（GPU模式下使用的CPU线程数）
     */
    private int cpuThreads = 4;

    /**
     * 创建默认配置
     *
     * @param modelPath ONNX模型文件路径
     * @return 配置实例
     */
    public static LaMaConfiguration createDefault(String modelPath) {
        return new LaMaConfiguration()
                .setModelPath(modelPath);
    }

    /**
     * 创建高质量配置
     * 使用更大的输入尺寸和更多的后处理选项
     *
     * @param modelPath ONNX模型文件路径
     * @return 高质量配置实例
     */
    public static LaMaConfiguration createHighQuality(String modelPath) {
        return new LaMaConfiguration()
                .setModelPath(modelPath)
                .setInputSize(1024)
                .setThreads(8)
                .setEnablePostProcessing(true)
                .setFeatherRadius(3)
                .setOutputQuality(0.98f);
    }

    /**
     * 创建快速配置
     * 使用较小的输入尺寸以提高处理速度
     *
     * @param modelPath ONNX模型文件路径
     * @return 快速配置实例
     */
    public static LaMaConfiguration createFast(String modelPath) {
        return new LaMaConfiguration()
                .setModelPath(modelPath)
                .setInputSize(256)
                .setThreads(2)
                .setEnablePostProcessing(false)
                .setFeatherRadius(1);
    }

    /**
     * 创建GPU配置
     * 启用GPU加速以提高处理速度
     *
     * @param modelPath ONNX模型文件路径
     * @return GPU配置实例
     */
    public static LaMaConfiguration createGpu(String modelPath) {
        // GPU模式下通常使用较少的CPU线程
        return new LaMaConfiguration()
                .setModelPath(modelPath)
                .setUseGpu(true)
                .setInputSize(512)
                .setCpuThreads(1);
    }

    /**
     * 创建自动mask配置
     * 启用自动mask生成功能
     *
     * @param modelPath ONNX模型文件路径
     * @param targetColor 需要移除的目标颜色
     * @return 自动mask配置实例
     */
    public static LaMaConfiguration createAutoMask(String modelPath, int[] targetColor) {
        return new LaMaConfiguration()
                .setModelPath(modelPath)
                .setAutoGenerateMask(true)
                .setTargetColor(targetColor)
                .setColorTolerance(30);
    }

    /**
     * 验证配置的有效性
     *
     * @throws IllegalArgumentException 如果配置无效
     */
    public void validate() {
        if (modelPath == null || modelPath.trim().isEmpty()) {
            throw new IllegalArgumentException("模型路径不能为空");
        }

        if (inputSize <= 0 || inputSize > 2048) {
            throw new IllegalArgumentException("输入尺寸必须在1-2048之间");
        }

        if (threads <= 0 || threads > 32) {
            throw new IllegalArgumentException("线程数必须在1-32之间");
        }

        if (maskThreshold < 0.0f || maskThreshold > 1.0f) {
            throw new IllegalArgumentException("mask阈值必须在0.0-1.0之间");
        }

        if (outputQuality < 0.0f || outputQuality > 1.0f) {
            throw new IllegalArgumentException("输出质量必须在0.0-1.0之间");
        }

        if (meanValues == null || meanValues.length != 3) {
            throw new IllegalArgumentException("均值必须包含3个值（RGB）");
        }

        if (stdValues == null || stdValues.length != 3) {
            throw new IllegalArgumentException("标准差必须包含3个值（RGB）");
        }

        if (targetColor == null || targetColor.length != 3) {
            throw new IllegalArgumentException("目标颜色必须包含3个值（RGB）");
        }

        for (int color : targetColor) {
            if (color < 0 || color > 255) {
                throw new IllegalArgumentException("颜色值必须在0-255之间");
            }
        }
    }

    /**
     * 获取输入张量的形状
     *
     * @return 张量形状 [批量, 通道, height, width]
     */
    public long[] getInputShape() {
        return new long[]{1, 3, inputSize, inputSize};
    }

    /**
     * 获取mask张量的形状
     *
     * @return mask张量形状 [批量, 通道, height, width]
     */
    public long[] getMaskShape() {
        return new long[]{1, 1, inputSize, inputSize};
    }

    /**
     * 克隆配置
     *
     * @return 配置的副本
     */
    public LaMaConfiguration clone() {
        LaMaConfiguration config = new LaMaConfiguration();
        config.modelPath = this.modelPath;
        config.inputSize = this.inputSize;
        config.threads = this.threads;
        config.useGpu = this.useGpu;
        config.gpuDeviceId = this.gpuDeviceId;
        config.meanValues = this.meanValues.clone();
        config.stdValues = this.stdValues.clone();
        config.maskThreshold = this.maskThreshold;
        config.useAlphaAsMask = this.useAlphaAsMask;
        config.autoGenerateMask = this.autoGenerateMask;
        config.colorTolerance = this.colorTolerance;
        config.targetColor = this.targetColor.clone();
        config.outputQuality = this.outputQuality;
        config.keepOriginalSize = this.keepOriginalSize;
        config.featherRadius = this.featherRadius;
        config.enablePostProcessing = this.enablePostProcessing;
        config.cpuThreads = this.cpuThreads;
        return config;
    }

    @Override
    /**
     * 转为字符串
    */
    public String toString() {
        
        return String.format("LaMaConfiguration{modelPath='%s', inputSize=%d, threads=%d, useGpu=%s}",
                modelPath, inputSize, threads, useGpu);
    
    }
}

