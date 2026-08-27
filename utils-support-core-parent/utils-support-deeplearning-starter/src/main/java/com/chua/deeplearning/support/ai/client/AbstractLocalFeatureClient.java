package com.chua.deeplearning.support.ai.client;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.feature.FeatureClientSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * 本地引擎特征提取客户端抽象基类。
 * <p>
 * 统一实现 {@link FeatureClient} 的公共逻辑：通过 {@link IdentificationEngine} 获取
 * 已注册的 String/byte[] → float[] 翻译器执行特征提取，并提供该引擎的模型列表。
 * 支持文本特征与图像特征两种输入模态。子类只需指定引擎名称。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractLocalFeatureClient implements FeatureClient {

    /**
     * 引擎名称（provider）
     */
    protected final String engine;

    /**
     * 识别引擎实例
     */
    protected final IdentificationEngine identificationEngine;

    /**
     * 当前模型名称
     */
    protected String model;

    /**
     * 输出向量维度
     */
    protected Integer dimensions;

    /**
     * 构造本地特征提取客户端。
     *
     * @param engine  引擎名称，如 "onnx"、"pytorch"、"llama"
     * @param setting 客户端配置
     */
    protected AbstractLocalFeatureClient(String engine, FeatureClientSetting setting) {
        this.engine = engine;
        this.identificationEngine = AbstractIdentificationEngine.getInstance();
        this.model = setting != null ? setting.getModel() : null;
        this.dimensions = setting != null ? setting.getDimensions() : null;
    }

    @Override
    /** Provider */
    public FeatureClient provider(String provider) {
        return this;
    }

    @Override
    /** Model */
    public FeatureClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** Dimensions */
    public FeatureClient dimensions(int dimensions) {
        this.dimensions = dimensions;
        return this;
    }

    /**
     * 解析实际使用的模型名称。
     *
     * <p>{@code auto} / 空值表示按当前服务器硬件配置自动挑选推荐模型，
     * 否则返回显式指定的模型名。</p>
     *
     * @return 模型名称
     */
    protected String resolveModel() {
        if (model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model)) {
            return model;
        }
        String recommended = DeeplearningModels.recommended(engine, null);
        if (recommended != null) {
            return recommended;
        }
        List<ModelDefinition> defs = models();
        if (defs.isEmpty()) {
            throw new IllegalStateException("引擎[" + engine + "]没有可用的特征模型");
        }
        return defs.get(0).getId();
    }

    @Override
    /** Extract */
    public float[] extract(String text) {
        String modelName = resolveModel();
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) identificationEngine.get(modelName, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = translator.translate(text);
        return toFloatArray(result, modelName);
    }

    @Override
    /** ExtractImage */
    public float[] extractImage(byte[] imageData) {
        String modelName = resolveModel();
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) identificationEngine.get(modelName, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = translator.translate(imageData);
        return toFloatArray(result, modelName);
    }

    /**
     * 将翻译器输出转换为 float 向量。
     *
     * @param result    翻译器输出
     * @param modelName 模型名称
     * @return float 向量
     */
    private static float[] toFloatArray(Object result, String modelName) {
        if (result instanceof float[] floats) {
            return floats;
        }
        if (result instanceof double[] doubles) {
            float[] out = new float[doubles.length];
            for (int i = 0; i < doubles.length; i++) {
                out[i] = (float) doubles[i];
            }
            return out;
        }
        throw new IllegalStateException("模型输出不是向量: " + modelName + " -> " + result);
    }

    @Override
    /** Models */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
