package com.chua.deeplearning.support.ai.client;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.DeviceSelector;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ConversationTranslator;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.translator.TranslatorUnwrappable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.chua.common.support.utils.StringUtils;

/**
 * 本地引擎文本对话客户端抽象基类。
 * <p>
 * 统一实现 {@link ChatClient} 的公共逻辑：通过 {@link IdentificationEngine} 获取
 * 已注册的 字符串→字符串 翻译器执行文本生成，并提供该引擎的模型列表。
 * 子类只需指定引擎名称（如 "onnx"、"pytorch"、"llama"）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractLocalChatClient implements ChatClient {

    /**
     * 引擎名称（提供者）
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
     * 客户端配置（用于在 对话同步 时向 translator 注入 device / usegpu / gpulayers 等参数）
     */
    protected final ChatClientSetting setting;

    /**
     * 系统提示词（{@code system()} 显式设置优先于 {@link ChatClientSetting#getSystem()}）
     */
    protected String systemPrompt;

    /**
     * 多轮对话历史（addUserHistory / addAssistantHistory / history 累积）
     */
    protected List<ChatMessage> historyMessages;

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

    /**
     * 模型
    */
    @Override
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
        if (StringUtils.isNotBlank(model) && !"auto".equalsIgnoreCase(model)) {
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
        return defs.getFirst().getId();
    }

    /**
     * 对话同步
    */
    @Override
    public String chatSync(String prompt) {
        return chatSync(prompt, 0);
    }

    /**
     * 对话同步
    */
    @Override
    public String chatSync(String prompt, long timeoutMillis) {
        String modelName = resolveModel();
        Map<String, Object> options = resolveOptions();
        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> translator =
                (ITranslator<Object, Object>) identificationEngine.get(modelName, ITranslator.class, options);
        if (translator == null) {
            throw new IllegalStateException("模型未注册: " + modelName);
        }
        // 懒加载包装会吞掉真实翻译器的能力判定（instanceof 打在包装上恒为否），
        // 先解包到最内层真实 translator，再按其真实能力分发
        Object effectiveTranslator = translator;
        if (effectiveTranslator instanceof TranslatorUnwrappable unwrappable) {
            Object inner = unwrappable.unwrapTranslator();
            if (inner != null) {
                effectiveTranslator = inner;
            }
        }
        List<ChatMessage> messages = buildConversationMessages(prompt);
        Object result;
        if (effectiveTranslator instanceof ConversationTranslator conversationTranslator) {
            // 支持多轮消息的翻译器：传入完整 system + 历史 + 本轮用户输入，由翻译器套用对话模板
            result = conversationTranslator.translate(messages);
        } else {
            // 普通翻译器：回退为纯文本拼接（仅一条用户消息时与历史行为完全一致）
            result = translator.translate(renderLegacyPrompt(messages));
        }
        return result != null ? result.toString() : null;
    }

    /**
     * 解析运行时参数（device / usegpu / gpulayers / ctx大小 / topk / temperature / topp /
     * 最大令牌 / threads / npredict）注入到 translator。
     *
     * <p>来源：{@link ChatClientSetting} 显式字段 + {@code deeplearning.device} 系统属性 + 硬编码默认。</p>
     *
     * <p>device 解析走 {@link DeviceSelector#resolve(String)}：auto/cpu/gpu/cuda → 归一化 cpu/gpu。
     * usegpu 走设置或系统属性；其他字段为 空 时由 translator 用自己的默认值。</p>
     *
     * @return 配置 映射，可为空
     */
    protected Map<String, Object> resolveOptions() {
        ChatClientSetting setting = this.setting;
        Map<String, Object> opts = Wraps.linkedMap();
        if (setting == null) {
            return opts;
        }
 // device 字段：优先 setting.devicesetting，其次 系统 prop deeplearning.device
        String deviceSetting = setting.getDeviceSetting();
        if (StringUtils.isBlank(deviceSetting)) {
            deviceSetting = System.getProperty(DeviceSelector.PROP);
        }
        if (StringUtils.isNotBlank(deviceSetting)) {
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

    /**
     * 对话同步with响应
    */
    @Override
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        String text = chatSync(prompt);
        return ChatSyncResponse.builder()
                .text(text)
                .build();
    }

    /**
     * 设置系统提示词。
     *
     * <p>优先级高于 {@link ChatClientSetting#getSystem()}；支持多轮消息的翻译器
     * 会将其作为 system 消息透传，普通翻译器以 {@code System:} 前缀拼入文本。</p>
     */
    @Override
    public ChatClient system(String system) {
        this.systemPrompt = system;
        return this;
    }

    /**
     * 添加用户历史消息（多轮对话上下文）。
     */
    @Override
    public ChatClient addUserHistory(String content) {
        addHistory("user", content);
        return this;
    }

    /**
     * 添加助手历史消息（多轮对话上下文）。
     */
    @Override
    public ChatClient addAssistantHistory(String content) {
        addHistory("assistant", content);
        return this;
    }

    /**
     * 设置完整对话历史（覆盖已有历史）。
     */
    @Override
    public ChatClient history(List<ChatMessage> messages) {
        this.historyMessages = messages == null ? null : new ArrayList<>(messages);
        return this;
    }

    /**
     * 追加一条历史消息，内容为空时忽略。
     *
     * @param role    消息角色（user / assistant）
     * @param content 消息内容
     */
    private void addHistory(String role, String content) {
        if (StringUtils.isBlank(content)) {
            return;
        }
        if (historyMessages == null) {
            historyMessages = new ArrayList<>();
        }
        historyMessages.add(ChatMessage.builder().role(role).content(content).build());
    }

    /**
     * 组装完整对话消息：系统提示词 + 历史 + 本轮用户输入。
     *
     * <p>系统提示词来源：{@code system()} 显式设置 &gt; {@link ChatClientSetting#getSystem()}。</p>
     *
     * @param prompt 本轮用户输入
     * @return 消息列表（按时间正序）
     */
    protected List<ChatMessage> buildConversationMessages(String prompt) {
        List<ChatMessage> messages = new ArrayList<>();
        String system = StringUtils.isNotBlank(systemPrompt) ? systemPrompt
                : (setting != null ? setting.getSystem() : null);
        if (StringUtils.isNotBlank(system)) {
            messages.add(ChatMessage.builder().role("system").content(system).build());
        }
        if (historyMessages != null) {
            for (ChatMessage message : historyMessages) {
                if (message != null && StringUtils.isNotBlank(message.getContent())) {
                    messages.add(message);
                }
            }
        }
        if (StringUtils.isNotBlank(prompt)) {
            messages.add(ChatMessage.builder().role("user").content(prompt).build());
        }
        return messages;
    }

    /**
     * 将消息列表渲染为单段文本，供不支持多轮消息的翻译器使用。
     *
     * <p>仅一条用户消息时原样返回（与历史行为完全一致）；否则按
     * {@code System:/User:/Assistant:} 前缀逐条拼接，尽力保留上下文。</p>
     *
     * @param messages 消息列表
     * @return 渲染后的文本
     */
    protected String renderLegacyPrompt(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        if (messages.size() == 1 && "user".equalsIgnoreCase(messages.getFirst().getRole())) {
            return messages.getFirst().getContent();
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            String role = message.getRole() == null || message.getRole().isBlank()
                    ? "user" : message.getRole();
            sb.append(Character.toUpperCase(role.charAt(0))).append(role.substring(1))
                    .append(": ").append(message.getContent()).append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * 模型
    */
    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine);
    }
}
