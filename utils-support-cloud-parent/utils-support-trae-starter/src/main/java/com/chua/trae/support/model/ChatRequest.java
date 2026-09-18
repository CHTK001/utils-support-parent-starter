package com.chua.trae.support.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
* 聊天请求，打开AI 风格消息列表 + 采样参数 + 工具定义。
* 通过 {@link Builder} 模式构造。
*
* @author CH
* @since 4.0.0.42
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    /** 模型标识，如 glm-5.2，可为 空（使用默认） */
    @JsonProperty("model")
    private String model; // 模型
    /** 消息列表，不可为 空 或空 */
    @JsonProperty("messages")
    private List<ChatMessage> messages; // 消息
    /** 是否流式，空 视为 true */
    @JsonProperty("stream")
    private Boolean stream; // 流
    /** 温度参数，0.0-2.0 */
    @JsonProperty("temperature")
    private Double temperature; // temperature
    /** 最大生成 令牌 数 */
    @JsonProperty("max_tokens")
    private Integer maxTokens; // 最大令牌
    /** Top-P 采样参数 */
    @JsonProperty("top_p")
    private Double topP; // topp
    /** 工具定义列表，可为 空 */
    @JsonProperty("tools")
    private List<ToolDefinition> tools; // tools
    /** 工具选择策略：auto/无/required */
    @JsonProperty("tool_choice")
    private String toolChoice; // toolchoice
    /** 扩展参数，透传至后端 */
    @JsonProperty("extra")
    private Map<String, Object> extra; // extra

    /**
    * 创建请求 构建器。
    *
    * @return Builder 实例
    */
    public static Builder builder() { return new Builder(); }

    /**
    * 模型标识。
    *
    * @return 模型名或 空
    */
    public String model() { return model; }

    /**
    * 设置模型。
    *
    * @param model 模型名
    */
    public void model(String model) { this.model = model; }

    /**
    * 消息列表。
    *
    * @return 消息列表
    */
    public List<ChatMessage> messages() { return messages; }

    /**
    * 设置消息列表。
    *
    * @param messages 消息列表
    */
    public void messages(List<ChatMessage> messages) { this.messages = messages; }

    /**
    * 是否流式。
    *
    * @return true 流式，false 非流式，空 默认流式
    */
    public Boolean stream() { return stream; }

    /**
    * 设置是否流式。
    *
    * @param stream 流式标志
    */
    public void stream(Boolean stream) { this.stream = stream; }

    /**
    * 温度参数。
    *
    * @return 温度或 空
    */
    public Double temperature() { return temperature; }

    /**
    * 设置温度。
    *
    * @param temperature 温度值
    */
    public void temperature(Double temperature) { this.temperature = temperature; }

    /**
    * 最大 令牌 数。
    *
    * @return 上限或 空
    */
    public Integer maxTokens() { return maxTokens; }

    /**
    * 设置最大 令牌 数。
    *
    * @param maxTokens 上限
    */
    public void maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    /**
    * Top-P 参数。
    *
    * @return Top-P 或 空
    */
    public Double topP() { return topP; }

    /**
    * 设置 Top-P。
    *
    * @param topP Top-P 值
    */
    public void topP(Double topP) { this.topP = topP; }

    /**
    * 工具定义列表。
    *
    * @return 工具列表或 空
    */
    public List<ToolDefinition> tools() { return tools; }

    /**
    * 设置工具定义。
    *
    * @param tools 工具列表
    */
    public void tools(List<ToolDefinition> tools) { this.tools = tools; }

    /**
    * 工具选择策略。
    *
    * @return 策略或 空
    */
    public String toolChoice() { return toolChoice; }

    /**
    * 设置工具选择策略。
    *
    * @param toolChoice 策略
    */
    public void toolChoice(String toolChoice) { this.toolChoice = toolChoice; }

    /**
    * 扩展参数。
    *
    * @return 扩展 映射 或 空
    */
    public Map<String, Object> extra() { return extra; }

    /**
    * 设置扩展参数。
    *
    * @param extra 扩展 映射
    */
    public void extra(Map<String, Object> extra) { this.extra = extra; }

    /**
    * 请求 构建器。
    * @author CH
    * @since 4.0.0
    */
    public static class Builder {
        /** 构建中的请求对象 */
        private final ChatRequest req = new ChatRequest();

        /**
        * 设置模型。
        *
        * @param model 模型名
        * @return 当前 构建器
        */
        public Builder model(String model) {
            req.model = model;
            return this;
        }

        /**
        * 设置消息列表。
        *
        * @param messages 消息列表
        * @return 当前 构建器
        */
        public Builder messages(List<ChatMessage> messages) {
            req.messages = messages;
            return this;
        }

        /**
        * 设置是否流式。
        *
        * @param stream 流式标志
        * @return 当前 构建器
        */
        public Builder stream(Boolean stream) {
            req.stream = stream;
            return this;
        }

        /**
        * 设置温度。
        *
        * @param t 温度值
        * @return 当前 构建器
        */
        public Builder temperature(Double t) {
            req.temperature = t;
            return this;
        }

        /**
        * 设置最大 令牌 数。
        *
        * @param m 上限
        * @return 当前 构建器
        */
        public Builder maxTokens(Integer m) {
            req.maxTokens = m;
            return this;
        }

        /**
        * 设置 Top-P。
        *
        * @param p Top-P 值
        * @return 当前 构建器
        */
        public Builder topP(Double p) {
            req.topP = p;
            return this;
        }

        /**
        * 设置工具定义。
        *
        * @param tools 工具列表
        * @return 当前 构建器
        */
        public Builder tools(List<ToolDefinition> tools) {
            req.tools = tools;
            return this;
        }

        /**
        * 设置工具选择策略。
        *
        * @param tc 策略
        * @return 当前 构建器
        */
        public Builder toolChoice(String tc) {
            req.toolChoice = tc;
            return this;
        }

        /**
        * 设置扩展参数。
        *
        * @param extra 扩展 映射
        * @return 当前 构建器
        */
        public Builder extra(Map<String, Object> extra) {
            req.extra = extra;
            return this;
        }

        /**
        * 构建请求。
        *
        * @return ChatRequest 实例
        */
        public ChatRequest build() { return req; }
    }

    /**
    * 用户消息。
    * @author CH
    * @since 4.0.0
    */
    public static class UserMessage implements ChatMessage {
        /** 角色，固定 用户 */
        @JsonProperty("role") public String role = "user";
        /** 文本内容 */
        @JsonProperty("content") public String content;
        /** 工具调用列表（多轮场景） */
        @JsonProperty("tool_calls") public List<ToolCall> toolCalls;

        /**
        * 无参构造，Jackson 用。
        */
        public UserMessage() {}

        /**
        * 创建用户消息。
        *
        * @param content 文本内容
        */
        public UserMessage(String content) { this.content = content; }

        @Override
        public String role() { return role; }

        /**
        * 文本内容。
        *
        * @return 内容
        */
        public String content() { return content; }

        /**
        * 工具调用列表。
        *
        * @return 调用列表或 空
        */
        public List<ToolCall> toolCalls() { return toolCalls; }

        /**
        * 设置工具调用列表。
        *
        * @param toolCalls 调用列表
        */
        public void toolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    }

    /**
    * 助手消息。
    * @author CH
    * @since 4.0.0
    */
    public static class AssistantMessage implements ChatMessage {
        /** 角色，固定 assistant */
        @JsonProperty("role") public String role = "assistant";
        /** 文本内容 */
        @JsonProperty("content") public String content;
        /** 工具调用列表 */
        @JsonProperty("tool_calls") public List<ToolCall> toolCalls;
        /** 推理内容（思维链） */
        @JsonProperty("reasoning_content") public String reasoningContent;

        /**
        * 无参构造，Jackson 用。
        */
        public AssistantMessage() {}

        /**
        * 创建助手消息。
        *
        * @param content 文本内容
        */
        public AssistantMessage(String content) { this.content = content; }

        @Override
        public String role() { return role; }

        /**
        * 文本内容。
        *
        * @return 内容
        */
        public String content() { return content; }

        /**
        * 设置文本内容。
        *
        * @param content 内容
        */
        public void content(String content) { this.content = content; }

        /**
        * 工具调用列表。
        *
        * @return 调用列表或 空
        */
        public List<ToolCall> toolCalls() { return toolCalls; }

        /**
        * 设置工具调用列表。
        *
        * @param toolCalls 调用列表
        */
        public void toolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

        /**
        * 推理内容。
        *
        * @return 推理文本或 空
        */
        public String reasoningContent() { return reasoningContent; }

        /**
        * 设置推理内容。
        *
        * @param reasoningContent 推理文本
        */
        public void reasoningContent(String reasoningContent) { this.reasoningContent = reasoningContent; }
    }

    /**
    * 工具结果消息。
    * @author CH
    * @since 4.0.0
    */
    public static class ToolMessage implements ChatMessage {
        /** 角色，固定 tool */
        @JsonProperty("role") public String role = "tool";
        /** 工具调用 标识，对应 toolcall.标识 */
        @JsonProperty("tool_call_id") public String toolCallId;
        /** 工具执行结果 */
        @JsonProperty("content") public String content;

        /**
        * 无参构造，Jackson 用。
        */
        public ToolMessage() {}

        /**
        * 创建工具结果消息。
        *
        * @param toolCallId 工具调用 标识
        * @param content 执行结果
        */
        public ToolMessage(String toolCallId, String content) {
            this.toolCallId = toolCallId;
            this.content = content;
        }

        @Override
        public String role() { return role; }

        /**
        * 工具调用 标识。
        *
        * @return 调用 标识
        */
        public String toolCallId() { return toolCallId; }

        /**
        * 工具执行结果。
        *
        * @return 结果文本
        */
        public String content() { return content; }
    }

    /**
    * 工具调用。
    * @author CH
    * @since 4.0.0
    */
    public static class ToolCall {
        /** 调用 标识 */
        @JsonProperty("id") private String id;
        /** 调用类型，固定 function */
        @JsonProperty("type") private String type = "function";
        /** 函数调用详情 */
        @JsonProperty("function") private FunctionCall function;
        /** 参数 映射（打开AI 风格） */
        @JsonProperty("arguments") private Map<String, Object> arguments;

        /**
        * 调用 标识。
        *
        * @return ID
        */
        public String id() { return id; }

        /**
        * 设置调用 标识。
        *
        * @param id 标识
        */
        public void id(String id) { this.id = id; }

        /**
        * 调用类型。
        *
        * @return 类型
        */
        public String type() { return type; }

        /**
        * 设置调用类型。
        *
        * @param type 类型
        */
        public void type(String type) { this.type = type; }

        /**
        * 函数调用详情。
        *
        * @return 函数或 空
        */
        public FunctionCall function() { return function; }

        /**
        * 设置函数调用详情。
        *
        * @param function 函数
        */
        public void function(FunctionCall function) { this.function = function; }

        /**
        * 参数 映射。
        *
        * @return 参数或 空
        */
        public Map<String, Object> arguments() { return arguments; }

        /**
        * 设置参数 映射。
        *
        * @param arguments 参数
        */
        public void arguments(Map<String, Object> arguments) { this.arguments = arguments; }
    }

    /**
    * 函数调用详情。
    * @author CH
    * @since 4.0.0
    */
    public static class FunctionCall {
        /** 函数名 */
        @JsonProperty("name") private String name;
        /** 参数 JSON 字符串 */
        @JsonProperty("arguments") private String arguments;

        /**
        * 无参构造，Jackson 用。
        */
        public FunctionCall() {}

        /**
        * 创建函数调用。
        *
        * @param name 函数名
        * @param arguments 参数 JSON
        */
        public FunctionCall(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        /**
        * 函数名。
        *
        * @return 名称
        */
        public String name() { return name; }

        /**
        * 设置函数名。
        *
        * @param name 名称
        */
        public void name(String name) { this.name = name; }

        /**
        * 参数 JSON。
        *
        * @return 参数字符串
        */
        public String arguments() { return arguments; }

        /**
        * 设置参数 JSON。
        *
        * @param arguments 参数字符串
        */
        public void arguments(String arguments) { this.arguments = arguments; }
    }

    /**
    * 工具定义。
    *
    * @param type 工具类型，固定 function
    * @param function 函数定义
    */
    public record ToolDefinition(
        @JsonProperty("type") String type,
        @JsonProperty("function") FunctionDef function
    ) {
        /**
        * 函数定义。
        *
        * @param name 函数名
        * @param description 函数描述
        * @param parameters 参数 JSON 模式
        */
        public record FunctionDef(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("parameters") Map<String, Object> parameters
        ) {}

        /**
        * 创建函数工具定义。
        *
        * @param name 函数名
        * @param description 函数描述
        * @param parameters 参数 JSON 模式
        * @return 工具定义
        */
        public static ToolDefinition of(String name, String description, Map<String, Object> parameters) {
            return new ToolDefinition("function", new FunctionDef(name, description, parameters));
        }
    }
}
