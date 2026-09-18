package com.chua.deeplearning.support.image;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.DetectOptions;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
* 图像分类器，对图像进行单标签或多标签分类。
*
* @author CH
* @since 4.0.0.42
 */
public interface ImageClassifier {

    /**
    * 创建图像分类器。
    *
    * @param name 模型名称
    * @return 分类器
    */

    /**
    * 通过 SPI 创建实例（提供者="onnx" 等）。
    *
    * @param provider 提供者 名称
    * @param apiKey   API 密钥（本地引擎可空）
    * @return 实例
    */
    static ImageClassifier create(String provider, String apiKey) {
        return ServiceProvider.of(ImageClassifier.class)
                .getNewExtension(provider, apiKey);
    }

    /**
    * 设置 提供者。
    *
    * @param provider 提供者 名称
    * @return this
    */
    default ImageClassifier provider(String provider) {
        return this;
    }

    /**
    * 设置模型名称。
    *
    * @param model 模型名称
    * @return this
    */
    default ImageClassifier model(String model) {
        return this;
    }

    /**
    * 创建
    *
    * @param name 名称
    * @return 创建的结果
    */
    static ImageClassifier create(String name) {
        return new DefaultImageClassifier(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
    * 查询该能力下全部可用模型。
    *
    * <p>按能力接口从 {@link com.chua.deeplearning.support.engine.ModelRegistry} 枚举
    * 全部已注册模型，供统一能力清单与前端按能力筛选使用。</p>
    *
    * @return 模型 标识 列表
    */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.image.ImageClassifier.class);
    }


    /**
    * 创建图像分类器。
    *
    * @param name    模型名称
    * @param setting 模型配置
    * @return 分类器
    */
    static ImageClassifier create(String name, ModelSetting setting) {
        return new DefaultImageClassifier(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
    * 设置 Top-K 分类数。
    *
    * @param k 返回的分类数
    * @return this
    */
    ImageClassifier topK(int k);

    /**
    * 设置模型路径。
    *
    * @param path 路径
    * @return this
    */
    ImageClassifier modelPath(String path);

    /**
    * 设置运行设备。
    *
    * @param device 设备
    * @return this
    */
    ImageClassifier device(String device);

    /**
    * 分类图像，返回最可能的类别。
    *
    * @param imageData 图像数据
    * @return 类别名称
    */
    String classify(byte[] imageData);

    /**
    * 分类图像，返回 Top-K 类别。
    *
    * @param imageData 图像数据
    * @param k         返回的类别数
    * @return 分类信息列表
    */
    List<DetectionInfo> classifyTopK(byte[] imageData, int k);

    /**
    * 设置置信度阈值（空 表示使用模型默认值）。
    *
    * @param threshold 阈值
    * @return this
    */
    default ImageClassifier threshold(float threshold) {
        return this;
    }
}

/**
* 默认图像分类器实现。
*
* @author CH
* @since 4.0.0.42
 */
class DefaultImageClassifier implements ImageClassifier {

    /**
    * 默认 Top-K 值。
    */
    private static final int DEFAULT_TOP_K = 5;

    /**
    * 默认运行设备（CPU）。
    */
    private static final String DEFAULT_DEVICE = "cpu";

    /**
    * 识别引擎。
    */
    /**
    * 置信度阈值（空 表示使用模型默认值）。
    */
    private Float threshold;

    private final IdentificationEngine engine; // engine

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
    * Top-K 分类数。
    */
    private int topK = DEFAULT_TOP_K;

    /**
    * 模型路径。
    */
    private String modelPath;

    /**
    * 运行设备。
    */
    private String device = DEFAULT_DEVICE;

    /**
    * 构造默认图像分类器。
    *
    * @param engine    识别引擎
    * @param modelName 模型名称
    * @param setting   模型配置
    */
    DefaultImageClassifier(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    /** 阈值 */
    public ImageClassifier threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** topk */
    public ImageClassifier topK(int k) {
        this.topK = k;
        return this;
    }

    @Override
    /** 模型路径 */
    public ImageClassifier modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public ImageClassifier device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * Classify
    *
    * @param imageData 镜像数据
    * @return classify的结果
    */
    public String classify(byte[] imageData) {
        ITranslator<byte[], String> t =
                (ITranslator<byte[], String>) engine.get(modelName, ITranslator.class, DetectOptions.of(threshold, null));
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * classifytopk
    *
    * @param imageData 镜像数据
    * @param k k
    * @return classifyTopK的结果
    */
    public List<DetectionInfo> classifyTopK(byte[] imageData, int k) {
        ITranslator<byte[], List<DetectionInfo>> t =
                (ITranslator<byte[], List<DetectionInfo>>) engine.get(modelName, ITranslator.class, DetectOptions.of(threshold, null));
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }
}
