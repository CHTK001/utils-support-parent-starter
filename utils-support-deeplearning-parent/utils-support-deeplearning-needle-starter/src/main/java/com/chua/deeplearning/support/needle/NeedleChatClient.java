package com.chua.deeplearning.support.needle;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.needle.NeedleNative;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 基于 Needle 推理引擎的本地对话客户端。
 *
 * <p>通过 Java 25 FFM 直接调用 Needle C 动态库，提供无网络的本地工具调用与
 * 结构化抽取能力，无需外部模型文件（权重内嵌于引擎）。</p>
 *
 * <p>用法：
 * <pre>{@code
 *   String answer = ChatClient.create("needle", "")
 *       .system("date: 2026-07-21 Tue 14:30")
 *       .chatSync("Invoke get_weather for Lagos.");
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
     * 系统提示词（环境事实）
     */
    private String system;

    /**
     * 当前模型名称
     */
    private String model;

    /**
     * 工具 JSON Schema 数组字符串
     */
    private String toolsJson;

    /**
     * 最大生成 token 数
     */
    private int maxTokens = 256;

    /**
     * 构造 Needle 对话客户端。
     *
     * @param setting 客户端配置
     */
    public NeedleChatClient(ChatClientSetting setting) {
        if (setting != null) {
            this.model = setting.getModel();
            this.system = setting.getSystem();
            if (setting.getMaxTokens() != null) {
                this.maxTokens = setting.getMaxTokens();
            }
        }
        this.toolsJson = "[]";
    }

    @Override
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public String chatSync(String prompt) {
        return chatSync(prompt, 0);
    }

    @Override
    public String chatSync(String prompt, long timeoutMillis) {
        NeedleNative.init(system, toolsJson, null);
        return NeedleNative.complete(prompt, maxTokens);
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
        ModelDefinition definition = ModelDefinition.builder()
                .id(model != null ? model : "needle2")
                .name("Needle 2")
                .provider("cactus-compute")
                .description("14MB foundation model for tool calling and structured extraction")
                .capabilities(List.of("tool-calling", "extraction", "json"))
                .build();
        return List.of(definition);
    }
}
