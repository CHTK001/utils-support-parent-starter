package com.chua.deeplearning.support.embedding;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入服务 —— 文本/图像向量化接口。
 * <p>通过 {@link IdentificationEngine} 加载的模型，将文本或图像转换为特征向量。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface EmbeddingService {

    /**
     * 根据模型名称创建嵌入服务。
     *
     * @param name 模型名称
     * @return EmbeddingService 实例
     */
    static EmbeddingService create(String name) {
        return new DefaultEmbeddingService(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
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
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.embedding.EmbeddingService.class);
    }


    /**
     * 根据模型名称和配置创建嵌入服务。
     *
     * @param name    模型名称
     * @param setting 模型配置
     * @return EmbeddingService 实例
     */
    static EmbeddingService create(String name, ModelSetting setting) {
        return new DefaultEmbeddingService(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模型路径。
     *
     * @param path 模型路径
     * @return this
     */
    default EmbeddingService modelPath(String path) {
        return this;
    }

    /**
     * 设置计算设备。
     *
     * @param device 设备名称（如 "cpu"、"gpu"）
     * @return this
     */
    default EmbeddingService device(String device) {
        return this;
    }

    /**
     * 将文本转换为向量。
     *
     * @param text 文本内容
     * @return 特征向量
     */
    float[] embed(String text);

    /**
     * 将图像数据转换为向量。
     *
     * @param imageData 图像字节数据
     * @return 特征向量
     */
    float[] embed(byte[] imageData);

    /**
     * 批量将文本转换为向量。
     *
     * @param texts 文本列表
     * @return 特征向量列表
     */
    List<float[]> embedBatch(List<String> texts);
}

/**
 * 默认嵌入服务实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultEmbeddingService implements EmbeddingService {

    /**
     * 识别引擎实例
     */
    private final IdentificationEngine engine;

    /**
     * 模型名称
     */
    private final String modelName;

    /**
     * 模型配置
     */
    @SuppressWarnings("unused")
    /** 设置 */
    private final ModelSetting setting;

    /**
    * 模型路径
    */
    private String modelPath;

    /**
     * 计算设备，默认 CPU
     */
    private String device = DEVICE_CPU;

    /**
     * 默认设备：CPU
     */
    private static final String DEVICE_CPU = "cpu";

    /**
     * 错误信息：模型未注册
     */
    private static final String MSG_MODEL_NOT_REGISTERED = "模型未注册: ";

    /**
     * 构造默认嵌入服务。
     *
     * @param engine    识别引擎
     * @param modelName 模型名称
     * @param setting   模型配置
     */
    DefaultEmbeddingService(IdentificationEngine engine, String modelName, ModelSetting setting) {
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
    /** 模型路径 */
    public EmbeddingService modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    /** Device */
    public EmbeddingService device(String device) {
        this.device = device;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * Embed
     *
     * @param text 文本
     * @return embed的结果
     */
    public float[] embed(String text) {
        ITranslator<String, float[]> t =
                (ITranslator<String, float[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException(MSG_MODEL_NOT_REGISTERED + modelName);
        }
        return t.translate(text);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * Embed
     *
     * @param imageData 镜像数据
     * @return embed的结果
     */
    public float[] embed(byte[] imageData) {
        ITranslator<byte[], float[]> t =
                (ITranslator<byte[], float[]>) engine.get(modelName, ITranslator.class);
        if (t == null) {
            throw new IllegalStateException(MSG_MODEL_NOT_REGISTERED + modelName);
        }
        return t.translate(imageData);
    }

    @Override
    /** embedbatch */
    public List<float[]> embedBatch(List<String> texts) {
        int size = texts.size();
        List<float[]> result = new ArrayList<>(size);
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }
}
