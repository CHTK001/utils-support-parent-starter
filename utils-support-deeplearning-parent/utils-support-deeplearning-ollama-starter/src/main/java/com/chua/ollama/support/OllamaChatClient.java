package com.chua.ollama.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.models.response.Model;
import io.github.ollama4j.utils.Options;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama 本地大模型对话客户端（SPI provider="ollama"）。
 *
 * <p>基于 ollama4j 原生 API（{@code /api/chat}）实现，支持纯文本对话、
 * 多轮历史、流式思考输出与用量统计。模型列表通过 ollama4j
 * {@code listModels()}（{@code GET /api/tags}）动态获取。
 * 默认地址 {@code http://localhost:11434}，无需 API Key。
 *
 * <p>调用示例：
 * <pre>{@code
 *   // 纯文本对话（需先 ollama pull minicpm5-2b）
 *   String answer = ChatClient.create("ollama", "")
 *       .model("minicpm5-2b")
 *       .temperature(1.0)
 *       .topP(0.95)
 *       .maxTokens(512)
 *       .chatSync("你好，介绍一下你自己");
 *
 *   // 多轮历史
 *   String reply = ChatClient.create("ollama", "")
 *       .model("minicpm5-2b")
 *       .addUserHistory("我叫小明")
 *       .chatSync("你还记得我叫什么吗？");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("ollama")
public class OllamaChatClient implements ChatClient {

    /**
     * 客户端 配置
     */
    private final ChatClientSetting setting;

    /**
     * ollama4j 原生 客户端
     */
    private final Ollama ollama;

    /**
     * 当前 模型 名称
     */
    private String model;

    /**
     * 系统 提示词
     */
    private String system;

    /**
     * 采样 温度
     */
    private Double temperature;

    /**
     * 最大 输出 令牌 数
     */
    private Integer maxTokens;

    /**
     * Top-P 采样
     */
    private Double topP;

    /**
     * 增量 历史（按 时间 正序）
     */
    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * 外部 一次性 历史（覆盖 增量 历史）
     */
    private List<ChatMessage> externalHistory;

    /**
     * 创建 Ollama 对话 客户端。
     *
     * @param setting 客户端 配置（provider 应为 "ollama"，apiKey 可为 空）
     */
    public OllamaChatClient(ChatClientSetting setting) {
        this.setting = setting;
        this.ollama = OllamaSupport.client(setting != null ? setting.getBaseUrl() : null);
        this.model = setting != null ? setting.getModel() : null;
        this.system = setting != null ? setting.getSystem() : null;
        this.temperature = setting != null ? setting.getTemperature() : null;
        this.maxTokens = setting != null ? setting.getMaxTokens() : null;
        this.topP = setting != null ? setting.getTopP() : null;
    }

    @Override
    /** 模型 */
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 系统 */
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    /** 温度 */
    public ChatClient temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    @Override
    /** 最大令牌 */
    public ChatClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    @Override
    /** Top-P */
    public ChatClient topP(Double topP) {
        this.topP = topP;
        return this;
    }

    @Override
    /** 用户历史 */
    public ChatClient addUserHistory(String content) {
        history.add(ChatMessage.builder().role("user").content(content).build());
        return this;
    }

    @Override
    /** 助手历史 */
    public ChatClient addAssistantHistory(String content) {
        history.add(ChatMessage.builder().role("assistant").content(content).build());
        return this;
    }

    @Override
    /** 历史 */
    public ChatClient history(List<ChatMessage> messages) {
        this.externalHistory = messages;
        return this;
    }

    @Override
    /** 新会话 */
    public ChatClient newChat() {
        this.history.clear();
        this.externalHistory = null;
        return this;
    }

    @Override
    /** 对话同步 */
    public String chatSync(String prompt) {
        return chatSyncWithResponse(prompt).getText();
    }

    @Override
    /** 对话同步带响应 */
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        OllamaChatRequest request = buildRequest(prompt);
        try {
            OllamaChatResult result = ollama.chat(request, null);
            OllamaChatMessage last = result.getResponseModel() != null
                    && result.getResponseModel().getMessage() != null
                    ? result.getResponseModel().getMessage()
                    : null;
            String text = mergeOutput(last);
            AiUsage usage = parseUsage(result.getResponseModel(), result.getChatHistory());
            return ChatSyncResponse.builder().text(text).usage(usage).build();
        } catch (Exception e) {
            throw OllamaSupport.wrap("chat", e);
        }
    }

    /**
    * 合并思考型模型的输出：优先返回有内容的 {@code thinking} 与 {@code response}。
    *
    * <p>MiniCPM5 等 深度思考 模型 会 把 推理 过程 放入 {@code thinking} 字段、
    * 最终 答案 放入 {@code response} 字段；部分 场景 仅 其一 非 空。
    * 此 方法 将 两者 拼接 为 完整 文本，避免 调用方 拿到 空 响应。
    * 若 两者 均 空 则 返回 空 字符串 而非 null。</p>
    *
    * @param message ollama4j 响应 消息（可为 空）
    * @return 合并 后 的 文本
    */
    private String mergeOutput(OllamaChatMessage message) {
        if (message == null) {
            return "";
        }
        String thinking = message.getThinking();
        String response = message.getResponse();
        StringBuilder sb = new StringBuilder();
        if (thinking != null && !thinking.isBlank()) {
            sb.append(thinking);
        }
        if (response != null && !response.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(response);
        }
        return sb.toString();
    }

    @Override
    /** 模型列表 */
    public List<ModelDefinition> models() {
        try {
            List<Model> raw = ollama.listModels();
            List<ModelDefinition> result = new ArrayList<>();
            if (raw != null) {
                for (Model m : raw) {
                    if (m == null || m.getName() == null || m.getName().isBlank()) {
                        continue;
                    }
                    result.add(ModelDefinition.builder()
                            .id(m.getName())
                            .name(m.getName())
                            .provider("ollama")
                            .description("本地 Ollama 模型")
                            .capabilities(List.of("chat"))
                            .build());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[Ollama] 获取 模型列表 异常: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        newChat();
    }

    /**
     * 构建 ollama4j 聊天 请求（模型、历史、当前 输入、采样 参数）。
     *
     * @param prompt 用户 输入
     * @return 聊天 请求
     */
    private OllamaChatRequest buildRequest(String prompt) {
        OllamaChatRequest request = OllamaChatRequest.builder()
                .withModel(model != null && !model.isBlank() ? model : "llama3.2:3b")
                .withMessages(buildOllamaMessages(prompt))
                .withOptions(buildOptions())
                .withGetJsonResponse();
        return request;
    }

    /**
     * 构建 ollama4j 消息 列表（系统 提示 + 历史 + 当前 输入）。
     *
     * @param prompt 用户 输入
     * @return 消息 列表
     */
    private List<OllamaChatMessage> buildOllamaMessages(String prompt) {
        List<OllamaChatMessage> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, system));
        }
        List<ChatMessage> effective = externalHistory != null ? externalHistory : history;
        for (ChatMessage msg : effective) {
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            OllamaChatMessageRole role = "assistant".equals(msg.getRole())
                    ? OllamaChatMessageRole.ASSISTANT
                    : OllamaChatMessageRole.USER;
            messages.add(new OllamaChatMessage(role, msg.getContent()));
        }
        messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, prompt != null ? prompt : ""));
        return messages;
    }

    /**
     * 构建采样 选项（temperature / num_predict / top_p）。
     *
     * @return 选项 构建器 结果
     */
    private Options buildOptions() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        if (temperature != null) {
            map.put("temperature", temperature);
        }
        if (maxTokens != null) {
            map.put("num_predict", maxTokens);
        }
        if (topP != null) {
            map.put("top_p", topP);
        }
        return Options.builder().optionsMap(map).build();
    }

    /**
     * 解析 令牌 用量（基于 ollama4j 响应 模型 的 评估 统计）。
     *
     * @param responseModel 响应 模型
     * @param history       聊天 历史（保留参数，便于 后续 扩展）
     * @return 用量 对象；无 用量 信息 时 返回 空
     */
    private AiUsage parseUsage(io.github.ollama4j.models.chat.OllamaChatResponseModel responseModel,
                               List<OllamaChatMessage> history) {
        if (responseModel == null) {
            return null;
        }
        Integer promptTokens = responseModel.getPromptEvalCount();
        Integer completionTokens = responseModel.getEvalCount();
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        int total = (promptTokens != null ? promptTokens : 0) + (completionTokens != null ? completionTokens : 0);
        return AiUsage.builder()
                .inputTokens(promptTokens)
                .outputTokens(completionTokens)
                .totalTokens(total > 0 ? total : null)
                .model(model)
                .provider("ollama")
                .build();
    }
}
