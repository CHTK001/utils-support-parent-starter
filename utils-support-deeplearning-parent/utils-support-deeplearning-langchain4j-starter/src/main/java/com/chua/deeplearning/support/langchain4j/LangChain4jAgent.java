package com.chua.deeplearning.support.langchain4j;

import com.chua.common.support.ai.agent.Agent;
import com.chua.common.support.ai.agent.AgentDefinition;
import com.chua.common.support.ai.agent.AgentMode;
import com.chua.common.support.ai.agent.AgentResponse;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.mcp.McpManager;
import com.chua.common.support.ai.mcp.McpToolCall;
import com.chua.common.support.ai.mcp.McpToolDescriptor;
import com.chua.common.support.ai.mcp.McpToolResult;
import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.ai.skill.SkillManager;
import com.chua.common.support.ai.skill.SkillResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
* 基于 langchain4j 实现的 re行为 Agent。
* 支持 MCP 工具调用、Skill 动态注入、工具链式推理，
* 通过 对话模型 与 LLM 交互，完成 re行为 循环。
*
* @author CH
* @since 4.0.0
 */
public class LangChain4jAgent implements Agent {

    /**
    * Agent 定义，包含系统提示词、最大工具调用次数等配置
    */
    private AgentDefinition definition;

    /**
    * Agent 运行模式：AUTO / TOOL / DIRECT 等
    */
    private AgentMode mode = AgentMode.AUTO;

    /**
    * 工具调用最大迭代次数，防止无限循环
    */
    private int maxToolIterations = 5;

    /**
    * 聊天客户端集合，键 为 Agent 标识
    */
    private final Map<String, ChatClient> chatClients = new HashMap<>();

    /**
    * 子 Agent 定义列表
    */
    private final List<AgentDefinition> subAgentDefinitions = new ArrayList<>();

    /**
    * 全局 MCP 管理器，用于动态注入 MCP 工具
    */
    private McpManager globalMcpManager;

    /**
    * 全局 Skill 管理器，用于动态注入 Skill 工具
    */
    private SkillManager globalSkillManager;

    /**
    * langchain4j 对话模型，Agent 实际与 LLM 交互的底层模型
    */
    private ChatModel chatModel;

    /**
    * 设置 Agent 运行模式
    *
    * @param mode 运行模式，空 时默认使用 AUTO
    * @return 当前 Agent 实例，支持链式调用
    */
    @Override
    public Agent mode(AgentMode mode) {
        this.mode = mode != null ? mode : AgentMode.AUTO;
        return this;
    }

    /**
    * 设置默认聊天客户端
    *
    * @param chatClient 聊天客户端实例
    * @return 当前 Agent 实例
    */
    @Override
    public Agent chatClient(ChatClient chatClient) {
        this.chatClients.put(null, chatClient);
        return this;
    }

    /**
    * 按 Agent 标识 设置聊天客户端
    *
    * @param agentId    Agent 标识
    * @param chatClient 聊天客户端实例
    * @return 当前 Agent 实例
    */
    @Override
    public Agent chatClient(String agentId, ChatClient chatClient) {
        this.chatClients.put(agentId, chatClient);
        return this;
    }

    /**
    * 设置全局 MCP 管理器
    *
    * @param mcpManager MCP 管理器实例
    * @return 当前 Agent 实例
    */
    @Override
    public Agent mcpManager(McpManager mcpManager) {
        this.globalMcpManager = mcpManager;
        return this;
    }

    /**
    * 按 Agent 标识 设置 MCP 管理器（仅首次设置生效）
    *
    * @param agentId    Agent 标识
    * @param mcpManager MCP 管理器实例
    * @return 当前 Agent 实例
    */
    @Override
    public Agent mcpManager(String agentId, McpManager mcpManager) {
        if (this.globalMcpManager == null) {
            this.globalMcpManager = mcpManager;
        }
        return this;
    }

    /**
    * 设置全局 Skill 管理器
    *
    * @param skillManager Skill 管理器实例
    * @return 当前 Agent 实例
    */
    @Override
    public Agent skillManager(SkillManager skillManager) {
        this.globalSkillManager = skillManager;
        return this;
    }

    /**
    * 按 Agent 标识 设置 Skill 管理器（仅首次设置生效）
    *
    * @param agentId      Agent 标识
    * @param skillManager Skill 管理器实例
    * @return 当前 Agent 实例
    */
    @Override
    public Agent skillManager(String agentId, SkillManager skillManager) {
        if (this.globalSkillManager == null) {
            this.globalSkillManager = skillManager;
        }
        return this;
    }

    /**
    * 注册子 Agent 定义
    *
    * @param subAgent 子 Agent 定义
    * @return 当前 Agent 实例
    */
    @Override
    public Agent subAgent(AgentDefinition subAgent) {
        if (subAgent != null) {
            this.subAgentDefinitions.add(subAgent);
        }
        return this;
    }

    /**
    * 注册自定义 Skill 工具（当前实现占位）
    *
    * @param name        Skill 名称
    * @param description Skill 描述
    * @param handler     处理器
    * @return 当前 Agent 实例
    */
    @Override
    public Agent skill(String name, String description, com.chua.common.support.ai.skill.SkillHandler handler) {
        return this;
    }

    /**
    * 开启或关闭 MCP 工具（当前实现占位）
    *
    * @param mcp 是否开启
    * @return 当前 Agent 实例
    */
    @Override
    public Agent mcp(boolean mcp) {
        return this;
    }

    /**
    * 设置工具调用最大迭代次数
    *
    * @param maxIterations 最大迭代次数
    * @return 当前 Agent 实例
    */
    @Override
    public Agent maxToolIterations(int maxIterations) {
        this.maxToolIterations = maxIterations;
        return this;
    }

    /**
    * 设置记忆配置（当前实现占位）
    *
    * @param memoryConfig 记忆配置
    * @return 当前 Agent 实例
    */
    @Override
    public Agent memoryConfig(com.chua.common.support.ai.memory.MemoryConfig memoryConfig) {
        return this;
    }

    /**
    * 设置上下文压缩配置（当前实现占位）
    *
    * @param compressionConfig 压缩配置
    * @return 当前 Agent 实例
    */
    @Override
    public Agent compressionConfig(com.chua.common.support.ai.agent.AgentCompressionConfig compressionConfig) {
        return this;
    }

    /**
    * 获取上下文压缩配置（当前实现占位）
    *
    * @return 始终返回 空
    */
    @Override
    public com.chua.common.support.ai.agent.AgentCompressionConfig compressionConfig() {
        return null;
    }

    /**
    * 开启或关闭规划模式（当前实现占位）
    *
    * @param plan 是否开启
    * @return 当前 Agent 实例
    */
    @Override
    public Agent plan(boolean plan) {
        return this;
    }

    /**
    * 设置规划最大任务数（当前实现占位）
    *
    * @param planMaxTask 最大任务数
    * @return 当前 Agent 实例
    */
    @Override
    public Agent planMaxTask(int planMaxTask) {
        return this;
    }

    /**
    * 设置调试钩子（当前实现占位）
    *
    * @param debugHook 调试钩子
    * @return 当前 Agent 实例
    */
    @Override
    public Agent debugHook(com.chua.common.support.ai.agent.AgentDebugHook debugHook) {
        return this;
    }

    /**
    * 设置规划钩子（当前实现占位）
    *
    * @param planHook 规划钩子
    * @return 当前 Agent 实例
    */
    @Override
    public Agent planHook(com.chua.common.support.ai.agent.AgentPlanHook planHook) {
        return this;
    }

    /**
    * 设置最大重试次数（当前实现占位）
    *
    * @param maxRetries 最大重试次数
    * @return 当前 Agent 实例
    */
    @Override
    public Agent maxRetries(int maxRetries) {
        return this;
    }

    /**
    * 设置重试退避策略（当前实现占位）
    *
    * @param strategy 退避策略
    * @return 当前 Agent 实例
    */
    @Override
    public Agent retryBackoff(com.chua.common.support.ai.agent.AgentRetryConfig.BackoffStrategy strategy) {
        return this;
    }

    /**
    * 设置重试基础延迟（当前实现占位）
    *
    * @param baseDelayMillis 基础延迟（毫秒）
    * @return 当前 Agent 实例
    */
    @Override
    public Agent retryBaseDelay(long baseDelayMillis) {
        return this;
    }

    /**
    * 设置完整重试配置（当前实现占位）
    *
    * @param retryConfig 重试配置
    * @return 当前 Agent 实例
    */
    @Override
    public Agent retryConfig(com.chua.common.support.ai.agent.AgentRetryConfig retryConfig) {
        return this;
    }

    /**
    * 获取 Agent 定义
    *
    * @return Agent 定义
    */
    @Override
    public AgentDefinition getDefinition() {
        return definition;
    }

    /**
    * 获取子 Agent 定义列表（返回不可变副本）
    *
    * @return 子 Agent 定义列表
    */
    @Override
    public List<AgentDefinition> getSubAgents() {
        return List.copyOf(subAgentDefinitions);
    }

    /**
    * 执行 Agent 推理。
    * 初始化对话上下文，构建工具规格，进入 ReAct 循环：
    * 调用 LLM → 检查是否触发工具调用 → 执行工具 → 追加结果 → 继续循环。
    * 循环次数受 最大tooliterations 限制，超出后返回当前最终输出。
    *
    * @param input 用户输入
    * @return Agent 响应结果
    */
    @Override
    public AgentResponse run(String input) {
        if (chatModel == null) {
            return AgentResponse.builder()
                    .output("")
                    .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                    .metadata(Map.of("error", "ChatModel 未配置，请先调用 chatModel(...)"))
                    .build();
        }

        int effectiveMaxIters = resolveMaxIters(
                this.maxToolIterations,
                definition != null ? definition.getMaxToolIterations() : 0);

        String systemPrompt = definition != null ? definition.getSystemPrompt() : "You are a helpful assistant.";
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            systemPrompt = "You are a helpful assistant.";
        }

        List<ToolSpecification> toolSpecs = buildToolSpecifications();
        Map<String, ToolExecutor> executors = buildToolExecutors();

        List<ChatMessage> history = new ArrayList<>();
        history.add(SystemMessage.from(systemPrompt));
        history.add(UserMessage.from(input));

        String finalOutput = "";
        int iter = 0;
        while (iter < effectiveMaxIters) {
            try {
                ChatResponse response = chatModel.chat(ChatRequest.builder()
                        .messages(history)
                        .toolSpecifications(toolSpecs)
                        .build());

                AiMessage aiMessage = response.aiMessage();
                if (aiMessage == null) {
                    break;
                }
                history.add(aiMessage);

                if (!aiMessage.hasToolExecutionRequests()) {
                    finalOutput = aiMessage.text() != null ? aiMessage.text() : "";
                    break;
                }

                List<ToolExecutionResultMessage> results = new ArrayList<>();
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    String resultText = executeTool(request, executors);
                    results.add(ToolExecutionResultMessage.from(request, resultText));
                }
                history.addAll(results);
                iter++;

            } catch (Exception e) {
                return AgentResponse.builder()
                        .output("")
                        .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                        .metadata(Map.of("error", e.getMessage()))
                        .build();
            }
        }

        return AgentResponse.builder()
                .output(finalOutput)
                .mode(mode != null ? mode.name() : AgentMode.AUTO.name())
                .build();
    }

    /**
    * 关闭 Agent，释放资源
    */
    @Override
    public void close() {
    }

    /**
    * 设置 langchain4j 对话模型
    *
    * @param chatModel 对话模型 实例
    * @return 当前 Agent 实例
    */
    public LangChain4jAgent chatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
        return this;
    }

    /**
    * 解析有效最大工具迭代次数。
    * 优先使用链式设置的值，其次使用 definition 中的值，
    * 两者均未设置时使用默认值 5。若最终值为非正数则使用 Integer.最大_值。
    *
    * @param chainValue 链式设置的值
    * @param defValue   定义中的值
    * @return 有效最大迭代次数
    */
    private static int resolveMaxIters(int chainValue, int defValue) {
        int effective = chainValue != 0 ? chainValue : (defValue != 0 ? defValue : 5);
        return effective > 0 ? effective : Integer.MAX_VALUE;
    }

    /**
    * 构建工具规格列表。
    * 从 MCP 管理器和 Skill 管理器中分别收集工具描述，
    * 转换为 langchain4j 的 toolspecification 格式。
    *
    * @return 工具规格列表
    */
    private List<ToolSpecification> buildToolSpecifications() {
        List<ToolSpecification> specs = new ArrayList<>();

        if (globalMcpManager != null) {
            for (McpToolDescriptor descriptor : globalMcpManager.listAllTools()) {
                JsonObjectSchema parameters = buildParametersFromSchema(descriptor.getInputSchema());
                specs.add(ToolSpecification.builder()
                        .name(descriptor.getName())
                        .description(descriptor.getDescription())
                        .parameters(parameters)
                        .build());
            }
        }

        if (globalSkillManager != null) {
            for (SkillDefinition skill : globalSkillManager.getAll().values()) {
                JsonObjectSchema parameters = buildParametersFromSkill(skill);
                specs.add(ToolSpecification.builder()
                        .name(skill.getName())
                        .description(skill.getDescription())
                        .parameters(parameters)
                        .build());
            }
        }

        return specs;
    }

    /**
    * 构建工具执行器映射。
    * 将 MCP 工具和 Skill 工具分别注册为 tool执行器，
    * 键 为工具名称，值 为执行函数。
    *
    * @return 工具名称到执行器的映射
    */
    private Map<String, ToolExecutor> buildToolExecutors() {
        Map<String, ToolExecutor> executors = new HashMap<>();

        if (globalMcpManager != null) {
            for (McpToolDescriptor descriptor : globalMcpManager.listAllTools()) {
                executors.put(descriptor.getName(), args -> {
                    String serverName = descriptor.getServerName();
                    if (serverName == null) {
                        serverName = "";
                    }
                    McpToolResult result = globalMcpManager.callTool(
                            serverName, new McpToolCall(descriptor.getName(), args));
                    return result.isSuccess()
                            ? String.valueOf(result.getContent())
                            : result.getErrorMessage();
                });
            }
        }

        if (globalSkillManager != null) {
            for (SkillDefinition skill : globalSkillManager.getAll().values()) {
                executors.put(skill.getName(), args -> {
                    SkillResult result = skill.execute(args);
                    return result.isSuccess()
                            ? String.valueOf(result.getContent())
                            : result.getErrorMessage();
                });
            }
        }

        return executors;
    }

    /**
    * 执行单个工具调用。
    * 根据工具名称从执行器映射中查找，解析参数 JSON，
    * 调用执行器并返回结果文本。
    *
    * @param request   工具执行请求
    * @param executors 工具执行器映射
    * @return 工具执行结果文本
    */
    private static String executeTool(
            ToolExecutionRequest request, Map<String, ToolExecutor> executors) {
        ToolExecutor executor = executors.get(request.name());
        if (executor == null) {
            return "Error: unknown tool '" + request.name() + "'";
        }
        try {
            Map<String, Object> args = parseArguments(request.arguments());
            return executor.execute(args);
        } catch (Exception e) {
            return "Error executing tool '" + request.name() + "': " + e.getMessage();
        }
    }

    /**
    * 解析工具调用参数 JSON 为 映射。
    * 空 JSON 或 {} 时返回空 映射。
    *
    * @param json JSON 字符串
    * @return 参数映射
    */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.trim().isEmpty() || json.trim().equals("{}")) {
            return Map.of();
        }
        return (Map<String, Object>) Json.fromJson(json, Map.class);
    }

    /**
    * 从 MCP 工具的 JSON 模式 构建 langchain4j 参数描述。
    * 将 模式 中的 属性 映射为 json字符串模式，
    * 提取 required 字段列表。
    *
    * @param schema MCP 工具的输入 模式
    * @return 参数描述对象
    */
    private static JsonObjectSchema buildParametersFromSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return JsonObjectSchema.builder().additionalProperties(true).build();
        }

        JsonObjectSchema.Builder builder = JsonObjectSchema.builder()
                .description("Parameters for the tool");

        Object properties = schema.get("properties");
        if (properties instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) properties).entrySet()) {
                builder.addProperty(entry.getKey(), JsonStringSchema.builder().build());
            }
        }

        Object required = schema.get("required");
        if (required instanceof List) {
            List<String> reqList = new ArrayList<>();
            for (Object item : (List<?>) required) {
                if (item instanceof String) {
                    reqList.add((String) item);
                }
            }
            if (!reqList.isEmpty()) {
                builder.required(reqList);
            }
        }

        return builder.build();
    }

    /**
    * 从 Skill 定义构建 langchain4j 参数描述。
    * 根据 Skill 参数类型（数字/integer/布尔值/enum/字符串）
    * 映射为对应的 json模式 子类。
    *
    * @param skill Skill 定义
    * @return 参数描述对象
    */
    private static JsonObjectSchema buildParametersFromSkill(SkillDefinition skill) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder()
                .description(skill.getDescription());
        List<String> requiredArgs = new ArrayList<>();

        for (com.chua.common.support.ai.skill.SkillArgumentSchema arg : skill.getArguments()) {
            JsonSchemaElement property = switch (arg.getType().toLowerCase()) {
                case "number" -> JsonNumberSchema.builder()
                        .description(arg.getDescription()).build();
                case "integer" -> JsonIntegerSchema.builder()
                        .description(arg.getDescription()).build();
                case "boolean" -> JsonBooleanSchema.builder()
                        .description(arg.getDescription()).build();
                case "enum" -> JsonEnumSchema.builder()
                        .description(arg.getDescription())
                        .enumValues(arg.getEnumValues() != null ? arg.getEnumValues() : List.of())
                        .build();
                default -> JsonStringSchema.builder()
                        .description(arg.getDescription()).build();
            };
            builder.addProperty(arg.getName(), property);
            if (arg.isRequired()) {
                requiredArgs.add(arg.getName());
            }
        }

        if (!requiredArgs.isEmpty()) {
            builder.required(requiredArgs);
        }

        return builder.build();
    }

    /**
    * 工具执行器函数式接口。
    * 接收参数映射，返回执行结果文本。
    * @author CH
    * @since 4.0.0
    */
    @FunctionalInterface
    private interface ToolExecutor {
        String execute(Map<String, Object> args) throws Exception;
    }
}
