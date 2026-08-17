package com.chua.deeplearning.support.ocr;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * OCR 文字识别器，从图像中提取文字内容和位置信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface OcrRecognizer {

    /**
     * 创建 OCR 识别器。
     *
     * @param name 模型名称
     * @return 识别器
     */

    /**
     * 通过 SPI 创建实例（provider="onnx" 等）。
     *
     * @param provider provider 名称
     * @param apiKey   API 密钥（本地引擎可空）
     * @return 实例
     */
    static OcrRecognizer create(String provider, String apiKey) {
        return ServiceProvider.of(OcrRecognizer.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default OcrRecognizer provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default OcrRecognizer model(String model) {
        return this;
    }

    static OcrRecognizer create(String name) {
        return new DefaultOcrRecognizer(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.ocr.OcrRecognizer.class);
    }


    /**
     * 创建 OCR 识别器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 识别器
     */
    static OcrRecognizer create(String name, ModelSetting setting) {
        return new DefaultOcrRecognizer(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置识别语言。
     *
     * @param lang 语言代码
     * @return this
     */
    OcrRecognizer lang(String lang);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    OcrRecognizer modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    OcrRecognizer device(String device);

    /**
     * 设置是否使用 GPU。
     *
     * @param useGpu 是否使用 GPU
     * @return this
     */
    OcrRecognizer useGpu(boolean useGpu);

    /**
     * 识别图像中的文字。
     *
     * @param imageData 图像数据
     * @return 识别文字
     */
    String recognize(byte[] imageData);

    /**
     * 识别图像中的文字，返回详细信息。
     *
     * @param imageData 图像数据
     * @return 识别结果详情列表
     */
    List<OcrResult> recognizeDetail(byte[] imageData);
}

/**
 * 默认 OCR 识别器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultOcrRecognizer implements OcrRecognizer {

    /**
     * 默认识别语言（中文）。
     */
    private static final String DEFAULT_LANG = "zh";

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
    private final ModelSetting setting;

    /**
     * 识别语言。
     */
    private String lang = DEFAULT_LANG;

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

    /**
     * 构造默认 OCR 识别器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultOcrRecognizer(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public OcrRecognizer lang(String lang) {
        this.lang = lang;
        return this;
    }

    @Override
    public OcrRecognizer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public OcrRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public OcrRecognizer useGpu(boolean useGpu) {
        this.useGpu = useGpu;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String recognize(byte[] imageData) {
        ITranslator<byte[], String> t =
                (ITranslator<byte[], String>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        ITranslator<byte[], List<OcrResult>> t =
                (ITranslator<byte[], List<OcrResult>>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
