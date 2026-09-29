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

    /**
     * 从模型注册表，键为 Agent 标识
     */
    private final Map<String, SlaveConfig> slaves = new LinkedHashMap<>(); // [P3C 3.15 豁免] 运行期 slave() 动态注册，规模不可预估

    /**
     * 主模型客户端（负责路由决策）
     */
    private ChatClient masterClient;

    /**
     * 主模型名称
     */
    private String masterModel = "default";

    /**
     * 执行模式
     */
    private AgentMode mode = AgentMode.ROUTER;

    /**
     * 内部 Agent 实例
     */
    private Agent agent;

    /**
     * 是否启用 MCP 工具
     */
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
     * @param mode 模式，不允许为 null
     * @return AgentChat客户端 对象
     */
    public AgentChatClient mode(AgentMode mode) {
        this.mode = mode;
        return this;
    }

    /**
     * 设置是否启用 MCP
     * @param mcp mcp（布尔开关）
     * @return AgentChat客户端 对象
     */
    public AgentChatClient mcp(boolean mcp) {
        this.mcp = mcp;
        return this;
    }

    /**
     * 获取Or创建Agent
     * @return Agent 对象
     */
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

    /**
     * ChatSync
     */
    @Override
    public String chatSync(String prompt) {
        AgentResponse result = getOrCreateAgent().run(prompt);
        return result != null ? result.getOutput() : "";
    }

    /**
     * Chat
     */
    @Override
    public void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {}, e -> { throw new RuntimeException(e); });
    }

    /**
     * 对话
     * @param prompt prompt
     * @param consumer consumer
     * @param onComplete onComplete
     * @param onError onError
     */
    @Override
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

    /**
     * Models
     */
    @Override
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

    /**
     * 关闭
     */
    @Override
    public void close() {
        if (agent != null) {
            agent.close();
        }
    }

    /**
     * 从模型注册项。
     *
     * <p>由 {@link AgentChatClient#slave(String, String, String, ChatClient)} 唯一构造，
     * 存放在 {@link #slaves} 注册表中；{@link #getOrCreateAgent()} 首次建 Agent 时把它转成
     * {@link AgentDefinition} 并把 {@link #client} 绑到对应 {@link #id} 上。四个组件均无校验，
     * 字符串组件允许为 {@code null}（但会拼进 instruction 产生「你是null」文本）。</p>
     *
     * @param id          从模型的 Agent 标识，同时作为 {@link #slaves} 的注册表键
     *                    （{@link LinkedHashMap#put} 语义，重复 id 后注册者覆盖先注册者）、
     *                    {@link AgentDefinition} 的 {@code id}，并作为
     *                    {@code agent.chatClient(id, client)} 的绑定键。主模型据此分派子任务
     *                    与回查后端客户端，须与 {@link #client} 成对一致。允许为 {@code null}，
     *                    但会成为 {@code null} 键并使路由无法定位该从模型
     * @param name        从模型的展示名称，写入 {@link AgentDefinition} 的 {@code name}，
     *                    同时被拼进系统提示词「你是 + name + ，+ description」。
     *                    取值来源为 {@link #slave(String, String, String, ChatClient)} 的入参
     * @param description 从模型的能力描述，写入 {@link AgentDefinition} 的 {@code description}，
     *                    同样拼进系统提示词；主模型正是依据这段描述判断该派给哪个子任务，
     *                    描述越具体分派越准。允许为 {@code null}
     * @param client      该从模型实际使用的 {@link ChatClient} 实例（承载真实模型调用），
     *                    在 {@link #getOrCreateAgent()} 中按 {@link #id} 绑定到 Agent。
     *                    允许为 {@code null}，此时该子任务被选中但无可用后端
     */
    private record SlaveConfig(String id, String name, String description, ChatClient client) {}
}
