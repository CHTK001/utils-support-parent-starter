package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.ArrayList;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 人脸识别器，提供人脸特征提取、特征比对和识别功能。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface FaceRecognizer {

    /**
     * 创建人脸识别器。
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
    static FaceRecognizer create(String provider, String apiKey) {
        return ServiceProvider.of(FaceRecognizer.class)
                .getNewExtension(provider, apiKey);
    }

    /**
     * 设置 provider。
     *
     * @param provider provider 名称
     * @return this
     */
    default FaceRecognizer provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称
     * @return this
     */
    default FaceRecognizer model(String model) {
        return this;
    }

    /** 创建 */
    static FaceRecognizer create(String name) {
        return new DefaultFaceRecognizer(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.face.FaceRecognizer.class);
    }


    /**
     * 创建人脸识别器。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return 识别器
     */
    static FaceRecognizer create(String name, ModelSetting setting) {
        return new DefaultFaceRecognizer(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置识别阈值。
     *
     * @param threshold 阈值
     * @return this
     */
    FaceRecognizer threshold(float threshold);

    /**
     * 设置模型路径。
     *
     * @param path 路径
     * @return this
     */
    FaceRecognizer modelPath(String path);

    /**
     * 设置运行设备。
     *
     * @param device 设备
     * @return this
     */
    FaceRecognizer device(String device);

    /**
     * 提取人脸特征。
     *
     * @param imageData 图像数据
     * @return 特征向量
     */
    float[] extractFeature(byte[] imageData);

    /**
     * 比较两个特征的相似度。
     *
     * @param feature1 特征一
     * @param feature2 特征二
     * @return 余弦相似度
     */
    float compare(float[] feature1, float[] feature2);

    /**
     * 识别人脸。
     *
     * @param imageData         查询图像
     * @param referenceFeatures 参考特征列表
     * @return 匹配的人脸特征列表
     */
    List<FaceFeature> recognize(byte[] imageData, List<float[]> referenceFeatures);
}

/**
 * 默认人脸识别器实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultFaceRecognizer implements FaceRecognizer {

    /**
     * 默认识别阈值。
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
     * 识别阈值。
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
     * 构造默认人脸识别器。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultFaceRecognizer(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    public FaceRecognizer threshold(float threshold) {
        this.threshold = threshold;
        return this;
    }

    @Override
    /** ModelPath */
    public FaceRecognizer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public FaceRecognizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** ExtractFeature */
    public float[] extractFeature(byte[] imageData) {
        ITranslator<byte[], float[]> t =
                (ITranslator<byte[], float[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    /** 比较 */
    public float compare(float[] feature1, float[] feature2) {
        float dot = 0, n1 = 0, n2 = 0;
        for (int i = 0; i < feature1.length; i++) {
            dot += feature1[i] * feature2[i];
            n1 += feature1[i] * feature1[i];
            n2 += feature2[i] * feature2[i];
        }
        return dot / (float) (Math.sqrt(n1) * Math.sqrt(n2));
    }

    @Override
    /** Recognize */
    public List<FaceFeature> recognize(byte[] imageData, List<float[]> referenceFeatures) {
        float[] query = extractFeature(imageData);
        List<FaceFeature> result = new ArrayList<>();
        for (float[] ref : referenceFeatures) {
            float score = compare(query, ref);
            if (score >= threshold) {
                result.add(new FaceFeature(imageData, ref, score));
            }
        }
        return result;
    }
}
