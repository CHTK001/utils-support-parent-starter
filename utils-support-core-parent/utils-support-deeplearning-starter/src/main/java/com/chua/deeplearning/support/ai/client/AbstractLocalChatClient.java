package com.chua.deeplearning.support.ai.client;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.DeviceSelector;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 客户端配置（用于在 chatSync 时向 translator 注入 device / useGpu / gpuLayers 等参数）
     */
    protected final ChatClientSetting setting;

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
        this.setting = setting;
    }

    @Override
    /** Model */
    public ChatClient model(String model) {
        this.model = model;
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
            throw new IllegalStateException("引擎[" + engine + "]没有可用的对话模型");
        }
        return defs.get(0).getId();
    }

    @Override
    /** ChatSync */
    public String chatSync(String prompt) {
        return chatSync(prompt, 0);
    }

    @Override
    /** ChatSync */
    public String chatSync(String prompt, long timeoutMillis) {
        String modelName = resolveModel();
        Map<String, Object> options = resolveOptions();
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) identificationEngine.get(modelName, ITranslator.class, options);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        Object result = translator.translate(prompt);
        return result != null ? result.toString() : null;
    }

    /**
     * 解析运行时参数（device / useGpu / gpuLayers / ctxSize / topK / temperature / topP /
     * maxTokens / threads / nPredict）注入到 translator。
     *
     * <p>来源：{@link ChatClientSetting} 显式字段 + {@code deeplearning.device} 系统属性 + 硬编码默认。</p>
     *
     * <p>device 解析走 {@link DeviceSelector#resolve(String)}：auto/cpu/gpu/cuda → 归一化 cpu/gpu。
     * useGpu 走设置或系统属性；其他字段为 null 时由 translator 用自己的默认值。</p>
     *
     * @return 配置 Map，可为空
     */
    protected Map<String, Object> resolveOptions() {
        ChatClientSetting setting = this.setting;
        Map<String, Object> opts = new LinkedHashMap<>();
        if (setting == null) {
            return opts;
        }
        // device 字段：优先 setting.deviceSetting，其次 system prop deeplearning.device
        String deviceSetting = setting.getDeviceSetting();
        if (deviceSetting == null || deviceSetting.isBlank()) {
            deviceSetting = System.getProperty(DeviceSelector.PROP);
        }
        if (deviceSetting != null && !deviceSetting.isBlank()) {
            opts.put("device", deviceSetting);
        }
        if (setting.getUseGpu() != null) {
            opts.put("useGpu", setting.getUseGpu());
        }
        if (setting.getGpuLayers() != null) {
            opts.put("gpuLayers", setting.getGpuLayers());
        }
        if (setting.getCtxSize() != null) {
            opts.put("ctxSize", setting.getCtxSize());
        }
        if (setting.getTopK() != null) {
            opts.put("topK", setting.getTopK());
        }
        if (setting.getThreads() != null) {
            opts.put("threads", setting.getThreads());
        }
        if (setting.getTemperature() != null) {
            opts.put("temperature", setting.getTemperature());
        }
        if (setting.getTopP() != null) {
            opts.put("topP", setting.getTopP());
        }
        if (setting.getMaxTokens() != null) {
            opts.put("nPredict", setting.getMaxTokens());
        }
        if (setting.getSeed() != null) {
            opts.put("seed", setting.getSeed());
        }
        if (setting.getStop() != null && !setting.getStop().isEmpty()) {
            opts.put("stop", setting.getStop());
        }
        return opts;
    }

    @Override
    /** ChatSyncWithResponse */
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        String text = chatSync(prompt);
        return ChatSyncResponse.builder()
                .text(text)
                .build();
    }

    @Override
    /** History */
    public ChatClient history(List<ChatMessage> messages) {
        return this;
    }

    @Override
    /** Models */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
