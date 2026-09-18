package com.chua.trae.support.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
* 聊天响应，打开AI 风格，包含 choices 列表与 令牌 用量。
*
* @author CH
* @since 4.0.0.42
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {

    /** 响应 标识，cmpl- 前缀 */
    @JsonProperty("id") private String id;
    /** 对象类型，固定 对话.完成 */
    @JsonProperty("object") private String object;
    /** 创建时间戳（秒） */
    @JsonProperty("created") private Long created;
    /** 使用的模型标识 */
    @JsonProperty("model") private String model;
    /** 候选结果列表 */
    @JsonProperty("choices") private List<Choice> choices;
    /** 令牌 用量统计 */
    @JsonProperty("usage") private Usage usage;

    /**
    * 响应 标识。
    *
    * @return ID
    */
    public String id() { return id; }

    /**
    * 设置响应 标识。
    *
    * @param id 标识
    */
    public void id(String id) { this.id = id; }

    /**
    * 对象类型。
    *
    * @return 类型
    */
    public String object() { return object; }

    /**
    * 设置对象类型。
    *
    * @param object 类型
    */
    public void object(String object) { this.object = object; }

    /**
    * 创建时间戳。
    *
    * @return 秒级时间戳
    */
    public Long created() { return created; }

    /**
    * 设置创建时间戳。
    *
    * @param created 时间戳
    */
    public void created(Long created) { this.created = created; }

    /**
    * 模型标识。
    *
    * @return 模型名
    */
    public String model() { return model; }

    /**
    * 设置模型标识。
    *
    * @param model 模型名
    */
    public void model(String model) { this.model = model; }

    /**
    * 候选结果列表。
    *
    * @return 结果列表
    */
    public List<Choice> choices() { return choices; }

    /**
    * 设置候选结果列表。
    *
    * @param choices 结果列表
    */
    public void choices(List<Choice> choices) { this.choices = choices; }

    /**
    * 令牌 用量。
    *
    * @return 用量或 空
    */
    public Usage usage() { return usage; }

    /**
    * 设置 令牌 用量。
    *
    * @param usage 用量
    */
    public void usage(Usage usage) { this.usage = usage; }

    /**
    * 候选结果，承载单条消息。
    * @author CH
    * @since 4.0.0
    */
    public static class Choice {
        /** 结果索引 */
        @JsonProperty("index") private Integer index;
        /** 完整消息（非流式） */
        @JsonProperty("message") private ResponseMessage message;
        /** 增量消息（流式） */
        @JsonProperty("delta") private ResponseMessage delta;
        /** 结束原因：停止/长度/tool_calls */
        @JsonProperty("finish_reason") private String finishReason;

        /**
        * 结果索引。
        *
        * @return 索引
        */
        public Integer index() { return index; }

        /**
        * 设置结果索引。
        *
        * @param index 索引
        */
        public void index(Integer index) { this.index = index; }

        /**
        * 完整消息。
        *
        * @return 消息或 空
        */
        public ResponseMessage message() { return message; }

        /**
        * 设置完整消息。
        *
        * @param message 消息
        */
        public void message(ResponseMessage message) { this.message = message; }

        /**
        * 增量消息。
        *
        * @return 增量或 空
        */
        public ResponseMessage delta() { return delta; }

        /**
        * 设置增量消息。
        *
        * @param delta 增量
        */
        public void delta(ResponseMessage delta) { this.delta = delta; }

        /**
        * 结束原因。
        *
        * @return 原因
        */
        public String finishReason() { return finishReason; }

        /**
        * 设置结束原因。
        *
        * @param finishReason 原因
        */
        public void finishReason(String finishReason) { this.finishReason = finishReason; }
    }

    /**
    * 响应消息，承载文本/推理/工具调用。
    * @author CH
    * @since 4.0.0
    */
    public static class ResponseMessage {
        /** 角色 */
        @JsonProperty("role") private String role;
        /** 文本内容 */
        @JsonProperty("content") private String content;
        /** 推理内容（思维链） */
        @JsonProperty("reasoning_content") private String reasoningContent;
        /** 工具调用列表 */
        @JsonProperty("tool_calls") private List<ChatRequest.ToolCall> toolCalls;

        /**
        * 角色。
        *
        * @return 角色名
        */
        public String role() { return role; }

        /**
        * 设置角色。
        *
        * @param role 角色名
        */
        public void role(String role) { this.role = role; }

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

        /**
        * 工具调用列表。
        *
        * @return 调用列表或 空
        */
        public List<ChatRequest.ToolCall> toolCalls() { return toolCalls; }

        /**
        * 设置工具调用列表。
        *
        * @param toolCalls 调用列表
        */
        public void toolCalls(List<ChatRequest.ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    }

    /**
    * 令牌 用量统计。
    * @author CH
    * @since 4.0.0
    */
    public static class Usage {
        /** 提示词 令牌 数 */
        @JsonProperty("prompt_tokens") private Integer promptTokens;
        /** 补全 令牌 数 */
        @JsonProperty("completion_tokens") private Integer completionTokens;
        /** 总 令牌 数 */
        @JsonProperty("total_tokens") private Integer totalTokens;

        /**
        * 无参构造，Jackson 用。
        */
        public Usage() {}

        /**
        * 创建用量统计。
        *
        * @param promptTokens 提示词 令牌 数
        * @param completionTokens 补全 令牌 数
        * @param totalTokens 总 令牌 数
        */
        public Usage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        /**
        * 提示词 令牌 数。
        *
        * @return 数量
        */
        public Integer promptTokens() { return promptTokens; }

        /**
        * 补全 令牌 数。
        *
        * @return 数量
        */
        public Integer completionTokens() { return completionTokens; }

        /**
        * 总 令牌 数。
        *
        * @return 数量
        */
        public Integer totalTokens() { return totalTokens; }
    }
}
