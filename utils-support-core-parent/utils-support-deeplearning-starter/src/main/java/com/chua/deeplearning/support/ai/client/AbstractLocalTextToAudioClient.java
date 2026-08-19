package com.chua.deeplearning.support.ai.client;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.audio.TextToAudioResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * 本地引擎文字转语音（TTS）客户端抽象基类。
 * <p>
 * 统一实现 {@link TextToAudioClient} 的公共逻辑：通过 {@link IdentificationEngine} 获取
 * 已注册的 String→byte[] 翻译器执行语音合成，并提供该引擎的模型列表。
 * 子类只需指定引擎名称（如 "onnx"、"llama"）。
 * </p>
 *
 * @since 4.0.0.42
 */
public abstract class AbstractLocalTextToAudioClient implements TextToAudioClient {

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
     * 当前合成文本
     */
    protected String text;

    /**
     * 构造本地 TTS 客户端。
     *
     * @param engine  引擎名称，如 "onnx"、"llama"
     * @param setting 客户端配置
     */
    protected AbstractLocalTextToAudioClient(String engine, TextToAudioClientSetting setting) {
        this.engine = engine;
        this.identificationEngine = AbstractIdentificationEngine.getInstance();
        this.model = setting != null ? setting.getModel() : null;
    }

    @Override
    public TextToAudioClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public TextToAudioClient text(String text) {
        this.text = text;
        return this;
    }

    /**
     * 解析实际使用的模型名称。
     *
     * @return 模型名称
     */
    protected String resolveModel() {
        if (model != null && !model.isBlank()) {
            return model;
        }
        List<ModelDefinition> defs = models();
        if (defs.isEmpty()) {
            throw new IllegalStateException("引擎[" + engine + "]没有可用的 TTS 模型");
        }
        return defs.get(0).getId();
    }

    @Override
    public byte[] synthesize(String text) {
        if (text != null) {
            this.text = text;
        }
        String modelName = resolveModel();
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) identificationEngine.get(modelName, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = translator.translate(this.text);
        if (result instanceof byte[] bytes) {
            return bytes;
        }
        throw new IllegalStateException("模型输出不是音频字节: " + modelName + " -> " + result);
    }

    @Override
    public String createTask(String text) {
        throw new UnsupportedOperationException("本地 TTS 不支持异步任务模式");
    }

    @Override
    public TextToAudioResponse queryTask(String taskId) {
        throw new UnsupportedOperationException("本地 TTS 不支持异步任务模式");
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
