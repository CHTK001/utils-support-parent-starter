package com.chua.image.support.filter.lama;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.image.support.filter.AbstractImageFilter;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * lama 图像修复滤镜
 *
 * 基于 lama (Large Mask Inpainting) 深度学习模型的智能图像修复滤镜。
 * lama 是一种先进的图像修复技术，能够高质量地填补图像中的缺失区域，
 * 移除不需要的对象，并智能地生成符合上下文的内容。
 *
 * 技术特点：
 * - 基于深度学习的图像修复算法
 * - 支持大面积区域的智能填充
 * - 保持图像的纹理和结构一致性
 * - 高质量的边缘融合效果
 * - 支持多种掩码输入模式
 *
 * 主要功能：
 * - 智能图像修复：自动填补图像中的缺失或损坏区域
 * - 对象移除：移除图像中不需要的对象并智能填充背景
 * - 多种掩码模式：支持 Alpha 通道、颜色检测、自定义掩码
 * - 高质量输出：基于深度学习的高质量图像生成
 * - 批量处理：支持批量图像修复操作
 *
 * 应用场景：
 * - 照片修复：修复老照片中的划痕和缺失部分
 * - 对象移除：从照片中移除不需要的人物或物体
 * - 图像清理：清除图像中的水印、文字或标记
 * - 艺术创作：为艺术作品填充创意内容
 * - 产品摄影：清理产品照片中的瑕疵
 *
 * <h3>使用要求</h3>
 * <ol>
 *   <li>添加 ONNX Runtime 依赖</li>
 *   <li>下载 lama ONNX 模型文件</li>
 *   <li>配置模型路径和推理参数</li>
 *   <li>准备输入图像和对应的掩码</li>
 * </ol>
 *
 * <h3>依赖配置</h3>
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
 * // 默认配置（CPU，512x512 输入）
 * LaMaImageFilter lama = new LaMaImageFilter("model/lama.onnx");
 *
 * // GPU 加速 + 高质量
 * LaMaImageFilter lama = new LaMaImageFilter(LaMaConfiguration.createHighQuality("model/lama.onnx"))
 *         .enableGpu();
 *
 * // 自动检测白色区域并移除（水印清除）
 * LaMaImageFilter lama = new LaMaImageFilter(LaMaConfiguration.createAutoMask(
 *         "model/lama.onnx", new int[]{255, 255, 255}));
 *
 * BufferedImage result = lama.converter(srcImage);
 *
 * // 用完关闭（释放 ONNX 资源）
 * lama.close();
 * }</pre>
 *
 * <h3>参数说明（构造器 + 链式方法）</h3>
 * <ul>
 *   <li><b>modelPath</b>（构造器参数，必填）：ONNX 模型文件路径。
 *       所有工厂方法（{@code createDefault/createHighQuality/createFast/createGpu/createAutoMask}）
 *       都以此为核心参数。</li>
 *   <li><b>config</b>：完整配置对象（{@link LaMaConfiguration}），
 *       控制输入尺寸、线程数、GPU、mask 策略、后处理等，详见 LaMaConfiguration 文档。</li>
 *   <li><b>customMask</b>（{@link #setCustomMask(BufferedImage)}）：自定义 mask 图像，
 *       白色区域将被修复。优先级高于自动 mask 策略。</li>
 * </ul>
 *
 * <h3>链式方法说明</h3>
 * <ul>
 *   <li><b>setModelPath(String)</b>：设置/更新模型路径，自动重载推理器</li>
 *   <li><b>setInputSize(int)</b>：设置输入尺寸（512/1024 等）</li>
 *   <li><b>enableAutoMask(int[] targetColor, int tolerance)</b>：启用自动 mask 生成（按颜色检测）</li>
 *   <li><b>enableAlphaMask()</b>：使用 Alpha 通道作为 mask</li>
 *   <li><b>enableGpu()</b>：启用 GPU 加速（需 GPU 版 ONNX Runtime）</li>
 *   <li><b>setPostProcessing(boolean, int)</b>：启用后处理 + 羽化半径</li>
 * </ul>
 *
 * <h3>性能特点</h3>
 * <ul>
 *   <li>GPU 加速支持（如果可用）</li>
 *   <li>内存优化的推理过程</li>
 *   <li>支持不同分辨率的图像</li>
 *   <li>可配置的推理参数</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>实现 {@link AutoCloseable}，用完后必须调用 {@code close()} 释放 ONNX 资源</li>
 *   <li>修复失败时返回原图（不抛异常），便于流水线容错</li>
 *   <li>输出格式默认为 PNG</li>
 * </ul>
 *
 * @author CH
 * @版本 1.0.0
 * @since 2024/7/29
 */
@Slf4j
@Spi("lama")
@SpiDescribe("LaMa图像修复滤镜")
public class LaMaImageFilter extends AbstractImageFilter implements AutoCloseable {

    /**
    * lama配置
    */
    private LaMaConfiguration config;

    /**
    * ONNX推理器
    */
    private LaMaOnnxInfer inferEngine;

    /**
    * 自定义mask图像
    */
    private BufferedImage customMask;

    /**
    * 默认构造函数
    * 需要后续调用设置配置()方法设置配置
    */
    public LaMaImageFilter() {
        // 默认构造函数，需要后续设置配置
    }

    /**
    * 构造函数
    *
    * @param modelPath ONNX模型文件路径
    */
    public LaMaImageFilter(String modelPath) {
        this(LaMaConfiguration.createDefault(modelPath));
    }

    /**
    * 构造函数
    *
    * @param config lama配置
    */
    public LaMaImageFilter(LaMaConfiguration config) {
        setConfig(config);
    }

    /**
    * 设置配置
    *
    * @param config lama配置
    * @return 当前实例
    */
    public LaMaImageFilter setConfig(LaMaConfiguration config) {
        this.config = config;
        
        // 关闭旧的推理器
        if (inferEngine != null) {
            inferEngine.close();
        }
        
        // 创建新的推理器
        try {
            this.inferEngine = new LaMaOnnxInfer(config);
            log.info("LaMa图像滤镜初始化成功: {}", config.getModelPath());
        } catch (Exception e) {
            log.error("LaMa图像滤镜初始化失败", e);
            throw new RuntimeException("LaMa图像滤镜初始化失败", e);
        }
        
        return this;
    }

    /**
    * 设置自定义mask
    *
    * @param mask mask图像，白色区域将被修复
    * @return 当前实例
    */
    public LaMaImageFilter setCustomMask(BufferedImage mask) {
        this.customMask = mask;
        return this;
    }

    /**
    * 设置模型路径
    *
    * @param modelPath ONNX模型文件路径
    * @return 当前实例
    */
    public LaMaImageFilter setModelPath(String modelPath) {
        if (config == null) {
            config = LaMaConfiguration.createDefault(modelPath);
        } else {
            config.setModelPath(modelPath);
        }
        return setConfig(config);
    }

    /**
    * 设置输入尺寸
    *
    * @param size 输入尺寸
    * @return 当前实例
    */
    public LaMaImageFilter setInputSize(int size) {
        ensureConfigExists();
        config.setInputSize(size);
        return setConfig(config);
    }

    /**
    * 启用自动mask生成
    *
    * @param targetColor 目标颜色（RGB）
    * @param tolerance   颜色容差
    * @return 当前实例
    */
    public LaMaImageFilter enableAutoMask(int[] targetColor, int tolerance) {
        ensureConfigExists();
        config.setAutoGenerateMask(true)
              .setTargetColor(targetColor)
              .setColorTolerance(tolerance);
        return setConfig(config);
    }

    /**
    * 启用alpha通道作为mask
    *
    * @return 当前实例
    */
    public LaMaImageFilter enableAlphaMask() {
        ensureConfigExists();
        config.setUseAlphaAsMask(true);
        return setConfig(config);
    }

    /**
    * 启用GPU加速
    *
    * @return 当前实例
    */
    public LaMaImageFilter enableGpu() {
        ensureConfigExists();
        config.setUseGpu(true);
        return setConfig(config);
    }

    /**
    * 设置后处理选项
    *
    * @param enablePostProcessing 是否启用后处理
    * @param featherRadius       羽化半径
    * @return 当前实例
    */
    public LaMaImageFilter setPostProcessing(boolean enablePostProcessing, int featherRadius) {
        ensureConfigExists();
        config.setEnablePostProcessing(enablePostProcessing)
              .setFeatherRadius(featherRadius);
        return setConfig(config);
    }

    @Override
    /** 过滤 */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        if (inferEngine == null || !inferEngine.isInitialized()) {
            throw new IllegalStateException("LaMa推理器未初始化，请先设置配置");
        }

        try {
            if (log.isDebugEnabled()) {
                log.debug("开始LaMa图像修复，输入尺寸: {}x{}", src.getWidth(), src.getHeight());
            }
            
            // 执行图像修复
            BufferedImage result = inferEngine.infer(src, customMask);
            
            if (log.isDebugEnabled()) {
                log.debug("LaMa图像修复完成，输出尺寸: {}x{}", result.getWidth(), result.getHeight());
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("LaMa图像修复失败", e);
            // 如果修复失败，返回原图
            return src;
        }
    }

    @Override
    /** 转换器 */
    public BufferedImage converter(BufferedImage image) throws IOException {
        return filter(image, null);
    }

    @Override
    /** 获取镜像格式化 */
    public String getImageFormat() {
        // png"; // LaMa输出通常使用PNG格式以保持质量
        return "png";
    }

    @Override
    /** 获取镜像格式化 */
    public String getImageFormat(String name) {
        
        return name != null ? name : getImageFormat();
    
    }

    /**
    * 获取配置信息
    *
    * @return 配置对象
    */
    public LaMaConfiguration getConfig() {
        return config;
    }

    /**
    * 获取推理器信息
    *
    * @return 推理器信息
    */
    public String getInferenceInfo() {
        if (inferEngine == null) {
            return "推理器未初始化";
        }
        return inferEngine.getModelInfo();
    }

    /**
    * 检查是否已准备就绪
    *
    * @return 是否可以使用
    */
    public boolean isReady() {
        return inferEngine != null && inferEngine.isInitialized();
    }

    /**
    * 预热模型
    * 使用小图像进行一次推理以预热模型，提高后续推理速度
    */
    public void warmUp() {
        if (!isReady()) {
            throw new IllegalStateException("推理器未初始化");
        }

        try {
            log.info("开始预热LaMa模型...");
            
            // 创建一个小的测试图像
            BufferedImage testImage = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            
            // 执行一次推理
            inferEngine.infer(testImage);
            
            log.info("LaMa模型预热完成");
            
        } catch (Exception e) {
            log.warn("模型预热失败，但不影响正常使用", e);
        }
    }

    /**
    * 确保配置存在
    */
    private void ensureConfigExists() {
        if (config == null) {
            throw new IllegalStateException("配置未设置，请先调用setConfig()或setModelPath()");
        }
    }

    /**
    * 清理资源
    */
    public void cleanup() {
        if (inferEngine != null) {
            inferEngine.close();
            inferEngine = null;
        }
        customMask = null;
        log.info("LaMa图像滤镜资源清理");
    }

    @Override
    /** 关闭 */
    public void close() {
        cleanup();
    }

    /**
    * 创建默认lama滤镜
    *
    * @param modelPath 模型路径
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createDefault(String modelPath) {
        return new LaMaImageFilter(modelPath);
    }

    /**
    * 创建高质量lama滤镜
    *
    * @param modelPath 模型路径
    * @return 高质量LaMa滤镜实例
    */
    public static LaMaImageFilter createHighQuality(String modelPath) {
        return new LaMaImageFilter(LaMaConfiguration.createHighQuality(modelPath));
    }

    /**
    * 创建快速lama滤镜
    *
    * @param modelPath 模型路径
    * @return 快速LaMa滤镜实例
    */
    public static LaMaImageFilter createFast(String modelPath) {
        return new LaMaImageFilter(LaMaConfiguration.createFast(modelPath));
    }

    /**
    * 创建GPU加速lama滤镜
    *
    * @param modelPath 模型路径
    * @return GPU加速LaMa滤镜实例
    */
    public static LaMaImageFilter createGpu(String modelPath) {
        return new LaMaImageFilter(LaMaConfiguration.createGpu(modelPath));
    }

    /**
    * 创建自动mask lama滤镜
    *
    * @param modelPath   模型路径
    * @param targetColor 目标颜色
    * @return 自动mask lama滤镜实例
    */
    public static LaMaImageFilter createAutoMask(String modelPath, int[] targetColor) {
        return new LaMaImageFilter(LaMaConfiguration.createAutoMask(modelPath, targetColor));
    }

}

