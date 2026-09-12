package com.chua.deeplearning.support.needle;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ChatTool;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.lang.json.Json5;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.needle.NeedleNative;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 基于 Needle 推理引擎的本地对话客户端。
 *
 * <p>通过 Java 25 FFM 直接调用 Needle C 动态库，提供无网络的本地对话能力，
 * 无需外部模型文件（权重内嵌于引擎）。</p>
 *
 * <h3>输入参数</h3>
 * <ul>
 *   <li>{@link #chatSync(String)} — 用户提示文本（必填）</li>
 *   <li>{@link #system(String)} — 系统提示词 / 环境事实，如 {@code "date: 2026-07-21 Tue 14:30"}</li>
 *   <li>{@link #model(String)} — 模型名称，默认 {@code needle2}</li>
 * </ul>
 *
 * <h3>输出参数</h3>
 * <ul>
 *   <li>{@link #chatSync(String)} 返回 {@code String} — 引擎生成的文本响应</li>
 *   <li>{@link #chatSyncWithResponse(String)} 返回 {@link ChatSyncResponse} — 含 text 字段的结构化响应</li>
 * </ul>
 *
 * <p>用法：
 * <pre>{@code
 *   String answer = ChatClient.create("needle", "")
 *       .system("date: 2026-07-21 Tue 14:30")
 *       .chatSync("你好，今天星期几？");
 * }</pre>tSync("你好，今天星期几？");
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("needle")
public class NeedleChatClient implements ChatClient {

    /**
      * 默认最大生成 令牌 数
     */
    private static final int DEFAULT_MAX_TOKENS = 256;

    /**
     * 系统提示词（环境事实）
     */
    private String system;

    /**
     * 当前模型名称
     */
    private String model;

    /**
      * 最大生成 令牌 数
     */
    private int maxTokens = DEFAULT_MAX_TOKENS;

    /**
     * 构造 Needle 对话客户端。
     *
     * @param setting 客户端配置（可为 空）
     */
    public NeedleChatClient(ChatClientSetting setting) {
        if (setting != null) {
            this.model = setting.getModel();
            this.system = setting.getSystem();
            if (setting.getMaxTokens() != null) {
                this.maxTokens = setting.getMaxTokens();
            }
        }
    }

    @Override
    /** 系统 */
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    /** 模型 */
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** Tools */
    public ChatClient tools(List<ChatTool> tools) {
        // Needle 引擎暂不支持工具调用
        return this;
    }

    @Override
    /** 对话同步 */
    public String chatSync(String prompt) {
        return chatSync(prompt, 0);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 对话同步
     *
     * @param prompt 提示符
     * @param timeoutMillis 超时millis
     * @return 对话同步的结果
     */
    public String chatSync(String prompt, long timeoutMillis) {
        NeedleNative.init(system, "[]", null);
        String raw = NeedleNative.complete(prompt, maxTokens);
        return extractText(raw);
    }

    @Override
    /** 对话同步with响应 */
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        String text = chatSync(prompt);
        return ChatSyncResponse.builder()
                .text(text)
                .build();
    }

    @Override
    /** 历史 */
    public ChatClient history(List<ChatMessage> messages) {
        return this;
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        ModelDefinition definition = ModelDefinition.builder()
                .id(model != null ? model : "needle2")
                .name("Needle 2")
                .provider("cactus-compute")
                .description("14MB foundation model for local chat and structured extraction")
                .capabilities(List.of("chat", "extraction", "json"))
                .build();
        return List.of(definition);
    }

    /**
     * 从引擎 JSON envelope 中提取文本响应。
     *
     * <p>引擎返回格式：
     * <ul>
     *   <li>{@code type=respond} — 含 {@code text} 字段，直接返回</li>
     *   <li>{@code type=call} — 工具调用类型，提取 {@code reasoning} 或返回原始 JSON</li>
     * </ul>
     *
     * @param raw 引擎原始输出
     * @return 用户可读的文本响应
     */
    @SuppressWarnings("unchecked")
    private String extractText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> envelope = Json5.fromJson(raw);
            String type = String.valueOf(envelope.get("type"));

            // type=respond：标准文本响应
            if ("respond".equals(type)) {
                Object text = envelope.get("text");
                return text != null ? String.valueOf(text) : raw;
            }

            // type=call：提取 reasoning 作为回复（无工具声明时引擎以此返回推理内容）
            if ("call".equals(type)) {
                Object reasoning = envelope.get("reasoning");
                if (reasoning != null && !String.valueOf(reasoning).isBlank()) {
                    return String.valueOf(reasoning);
                }
 // 有 function_calls 但无 ReasonMLML，返回原始 JSON
                return raw;
            }

            // 未知类型，原样返回
            return raw;
        } catch (Exception e) {
            // 非 JSON 格式，直接作为文本返回
            return raw;
        }
    }
}