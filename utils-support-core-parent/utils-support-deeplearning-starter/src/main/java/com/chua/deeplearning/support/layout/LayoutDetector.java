package com.chua.deeplearning.support.layout;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import java.util.Map;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 版面检测器，检测文档中不同区域的布局（文字、表格、图片等）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface LayoutDetector {

    /**
     * 使用默认模型创建版面检测器。
     *
     * @return 检测器
     */

    /**
     * 通过 SPI 创建实例（provider="onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static LayoutDetector create(String provider, String apiKey) {
        return ServiceProvider.of(LayoutDetector.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default LayoutDetector provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default LayoutDetector model(String model) {
        return this;
    }

    /** 创建 */
    static LayoutDetector create() {
        return new DefaultLayoutDetector(AbstractIdentificationEngine.getInstance(), "", ModelSetting.builder().build());
    }

    /**
     * 创建版面检测器。
     *
     * @param name 模型名称
     * @return 检测器
     */
    static LayoutDetector create(String name) {
        return new DefaultLayoutDetector(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
     * 查询该能力下全部可用模型。
     *
     * <p>按能力接口从 {@link com.chua.deeplearning.support.engine.ModelRegistry} 枚举
     * 全部已注册模型，供统一能力清单与前端按能力筛选使用。</p>
     *
     * @return 模型 ID 列表
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.layout.LayoutDetector.class);
    }


    /**
     * 创建版面检测器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 检测器
     */
    static LayoutDetector create(String name, ModelSetting setting) {
        return new DefaultLayoutDetector(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置检测阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    LayoutDetector threshold(float threshold);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    LayoutDetector modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    LayoutDetector device(String device);

    /**
     * 设置是否使用 GPU。
     *
     * @param useGpu 是否使用 GPU
     * @return this
     */
    LayoutDetector useGpu(boolean useGpu);

    /**
     * 检测文档版面。
     *
     * @param imageData 图像数据
     * @return 按区域类型分组的检测框
     */
    Map<String, List<PredictRectangle>> detect(byte[] imageData);

    /**
     * 解析文档版面为结构化文本（Markdown）。
     * <p>端到端版面解析模型（如 OvisOCR2、Unlimited-OCR）直接输出结构化 Markdown 文本，
     * 包含表格 HTML、公式 LaTeX 和图片坐标。
     * 传统检测框模型默认不支持此方法。</p>
     *
     * @param imageData 图像数据
     * @return Markdown 格式的文档内容
     * @throws UnsupportedOperationException 若模型不支持文本输出
     */
    String parse(byte[] imageData);
}

/**
 * 默认版面检测器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultLayoutDetector implements LayoutDetector {

    /**
     * 默认检测阈值。
     */
    private static final float DEFAULT_THRESHOLD = 0.5f;

    /**
     * 默认运行设备（CPU）。
     */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
     * 识别引擎。
     */
    private final IdentificationEngine engine;

    /**
     * 模型名称。
     */
    private final String modelName;

    /**
     * 模型配置。
     */
    @SuppressWarnings("unused")
    /** 设置 */
    private final ModelSetting setting;

    /**
     * 检测阈值。
     */
    private float threshold = DEFAULT_THRESHOLD;

    /**
     * 模型路径。
     */
    private String modelPath;

    /**
     * 运行设备。
     */
    private String device = DEFAULT_DEVICE;

    /**
     * 是否使用 GPU。
     */
    private boolean useGpu;

    DefaultLayoutDetector(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName;
        this.setting = setting;
        if (setting.getModelPath() != null) {
            this.modelPath = setting.getModelPath();
        }
        if (setting.getDevice() != null) {
            this.device = setting.getDevice();
        }
    }

    @Override
    /** Threshold */
    public LayoutDetector threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** ModelPath */
    public LayoutDetector modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public LayoutDetector device(String device) {
        this.device = device;
        return this;
    }

    @Override
    /** UseGpu */
    public LayoutDetector useGpu(boolean useGpu) {
        this.useGpu = useGpu;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** Detect */
    public Map<String, List<PredictRectangle>> detect(byte[] imageData) {
        ITranslator<byte[], Map<String, List<PredictRectangle>>> t =
                (ITranslator<byte[], Map<String, List<PredictRectangle>>>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 解析 */
    public String parse(byte[] imageData) {
        ITranslator<byte[], Object> t = (ITranslator<byte[], Object>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = t.translate(imageData);
        if (result instanceof String txt) {
            return txt;  // 端到端解析器（OvisOCR2）直接返回 Markdown
        }
        // 传统检测框模型 → 按区域类型名拼接
        Map<String, List<PredictRectangle>> regions = (Map<String, List<PredictRectangle>>) result;
        StringBuilder sb = new StringBuilder();
        for (var entry : regions.entrySet()) {
            for (var rect : entry.getValue()) {
                sb.append(rect.labelName()).append(" ");
            }
        }
        return sb.toString().trim();
    }
}
