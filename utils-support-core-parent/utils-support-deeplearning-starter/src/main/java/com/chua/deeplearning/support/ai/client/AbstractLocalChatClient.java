package com.chua.deeplearning.support.ai.client;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.List;

/**
 * 本地引擎文本对话客户端抽象基类。
 * <p>
 * 统一实现 {@link ChatClient} 的公共逻辑：通过 {@link IdentificationEngine} 获取
 * 已注册的 String→String 翻译器执行文本生成，并提供该引擎的模型列表。
 * 子类只需指定引擎名称（如 "onnx"、"pytorch"、"llama"）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractLocalChatClient implements ChatClient {

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
     * 构造本地对话客户端。
     *
     * @param engine  引擎名称，如 "onnx"、"pytorch"、"llama"
     * @param setting 客户端配置
     */
    protected AbstractLocalChatClient(String engine, ChatClientSetting setting) {
        this.engine = engine;
        this.identificationEngine = AbstractIdentificationEngine.getInstance();
        this.model = setting != null ? setting.getModel() : null;
    }

    @Override
    public ChatClient model(String model) {
        this.model = model;
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
            throw new IllegalStateException("引擎[" + engine + "]没有可用的对话模型");
        }
        return defs.get(0).getId();
    }

    @Override
    public String chatSync(String prompt) {
        return chatSync(prompt, 0);
    }

    @Override
    public String chatSync(String prompt, long timeoutMillis) {
        String modelName = resolveModel();
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) identificationEngine.get(modelName, ITranslator.class);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = translator.translate(prompt);
        return result != null ? result.toString() : null;
    }

    @Override
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        String text = chatSync(prompt);
        return ChatSyncResponse.builder()
                .text(text)
                .build();
    }

    @Override
    public ChatClient history(List<ChatMessage> messages) {
        return this;
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
