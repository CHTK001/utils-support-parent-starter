package com.chua.common.support.ai.chat;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 路由对话客户端
 *
 * <p>将 Agent 的多模型路由能力包装为 {@link ChatClient} 接口。
 * 支持配置一个主模型（master）和多个从模型（slave），
 * 主模型负责意图识别和任务分派，从模型负责具体子任务执行。
 *
 * <p>MCP 默认关闭，Agent 退化为纯路由模型，不加载任何外部工具。
 *
 * <p>使用示例：
 * <pre>{@code
 *   ChatClient client = new AgentChatClient()
 *       .master("gpt-4", openAiClient)
 *       .slave("dev-agent", "开发助手", "代码开发专家", deepseekClient)
 *       .slave("search-agent", "搜索助手", "联网搜索", searchClient)
 *       .mode(AgentMode.ROUTER);
 *
 *   String answer = client.chatSync("帮我写一个快速排序");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.41
 */
public class AgentChatClient implements ChatClient {

    /** 从模型注册表，键为 Agent 标识 */
    private final Map<String, SlaveConfig> slaves = new LinkedHashMap<>();

    /** 主模型客户端（负责路由决策） */
    private ChatClient masterClient;

    /** 主模型名称 */
    private String masterModel = "default";

    /** 执行模式 */
    private AgentMode mode = AgentMode.ROUTER;

    /** 内部 Agent 实例 */
    private Agent agent;

    /** 是否启用 MCP 工具 */
    private boolean mcp = false;

    /**
     * 配置主模型（负责路由决策）
     *
     * @param modelName 模型名称
     * @param client    ChatClient 实例
     * @return 当前实例
     */
    public AgentChatClient master(String modelName, ChatClient client) {
        this.masterClient = client;
        this.masterModel = modelName;
        return this;
    }

    /**
     * 注册从模型（负责具体子任务）
     *
     * @param id          Agent 标识
     * @param name        Agent 名称
     * @param description Agent 描述
     * @param client      ChatClient 实例
     * @return 当前实例
     */
    public AgentChatClient slave(String id, String name, String description, ChatClient client) {
        slaves.put(id, new SlaveConfig(id, name, description, client));
        return this;
    }

    /**
     * 设置执行模式
     */
    public AgentChatClient mode(AgentMode mode) {
        this.mode = mode;
        return this;
    }

    /**
     * 设置是否启用 MCP
     */
    public AgentChatClient mcp(boolean mcp) {
        this.mcp = mcp;
        return this;
    }

    /** 获取Or创建Agent */
    private Agent getOrCreateAgent() {
        if (agent != null) {
            return agent;
        }
        agent = Agent.create("agentscope")
                .mode(mode)
                .mcp(mcp)
                .chatClient(masterClient);
        for (SlaveConfig slave : slaves.values()) {
            AgentDefinition def = AgentDefinition.builder()
                    .id(slave.id)
                    .name(slave.name)
                    .description(slave.description)
                    .instruction("你是" + slave.name + "，" + slave.description)
                    .build();
            agent.subAgent(def);
            agent.chatClient(slave.id, slave.client);
        }
        return agent;
    }

    @Override
    /** ChatSync */
    public String chatSync(String prompt) {
        AgentResponse result = getOrCreateAgent().run(prompt);
        return result != null ? result.getOutput() : "";
    }

    @Override
    /** Chat */
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
            consumer.accept(ChatResponse.builder().state(ChatResponse.State.STOP).build());
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
    /** Models */
    public List<ModelDefinition> models() {
        Map<String, AgentDefinition> defs = getOrCreateAgent().getSubAgents().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AgentDefinition::getId, d -> d, (a, b) -> a));
        return List.of(ModelDefinition.builder()
                .id("agent-router")
                .name("Agent Router (" + masterModel + ")")
                .provider("agent")
                .description("多模型路由，主模型: " + masterModel + "，从模型: " + String.join(", ", defs.keySet()))
                .capabilities(List.of("chat", "routing"))
                .build());
    }

    @Override
    /** 关闭 */
    public void close() {
        if (agent != null) {
            agent.close();
        }
    }

    /** SlaveConfig */
    private record SlaveConfig(String id, String name, String description, ChatClient client) {}
}