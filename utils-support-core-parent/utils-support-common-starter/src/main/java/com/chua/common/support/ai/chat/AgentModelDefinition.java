package com.chua.common.support.ai.chat;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;

/**
 * Agent 模型定义 — 将 Agent 包装为 ChatClient。
 *
 * <p>默认名称 {@code "router-auto"}，调用时内部走 Agent 的 ROUTER/AUTO 路由。
 * 用户需预先配置 Agent（子 Agent、MCP、技能等），然后通过 ChatClient 接口调用。
 *
 * <p>使用示例：
 * <pre>{@code
 * Agent agent = Agent.create("agentscope")
 *     .chatClient(openAiClient)
 *     .subAgent(devAgent)
 *     .subAgent(searchAgent);
 *
 * ChatClient client = new AgentModelDefinition(agent);
 * String answer = client.chatSync("帮我查天气");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.41
 */
@Slf4j
public class AgentModelDefinition implements ChatClient {

    /**
     * 默认模型名称
    */
    private static final String DEFAULT_MODEL = "router-auto";

    /**
     * 内部 Agent 实例
    */
    private final Agent agent;

    /**
     * 模型名称
    */
    private String model = DEFAULT_MODEL;

    /**
     * 最近一次 Agent 响应
    */
    private AgentResponse lastResponse;

    /**
     * 创建 AgentModelDefinition 实例
     * @param agent agent
     */
    public AgentModelDefinition(Agent agent) {
        this.agent = agent;
    }

    /**
     * 获取内部 Agent 实例。
     *
     * @return Agent 实例
     */
    public Agent getAgent() {
        return agent;
    }

    @Override
    /**
     * Model
    */
    public ChatClient model(String model) {
        this.model = model != null ? model : DEFAULT_MODEL;
        return this;
    }

    @Override
    /**
     * ChatSync
    */
    public String chatSync(String prompt) {
        lastResponse = agent.run(prompt);
        return lastResponse != null ? lastResponse.getOutput() : "";
    }

    @Override
    /**
     * Chat
    */
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {}, e -> { throw new RuntimeException(e); });
    }

    @Override
    /**
     * 对话
     * @param prompt prompt
     * @param consumer consumer
     * @param onComplete onComplete
     * @param onError onError
     */
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        try {
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.START).build());
            String output = chatSync(prompt);
            if (output != null && !output.isEmpty()) {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STREAMING)
                        .content(output)
                        .build());
            }
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .usage(lastResponse != null ? lastResponse.getUsage() : null)
                    .build());
            onComplete.run();
        } catch (Exception e) {
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    @Override
    /**
     * Models
    */
    public List<ModelDefinition> models() {
        return List.of(ModelDefinition.builder()
                .id(DEFAULT_MODEL)
                .name("Agent Router Auto")
                .provider("agent")
                .description("智能 Agent 路由模型，自动识别用户意图并委派给合适的子 Agent 或直接回答")
                .capabilities(List.of("chat", "tools", "skills", "routing"))
                .build());
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        agent.close();
    }
}
