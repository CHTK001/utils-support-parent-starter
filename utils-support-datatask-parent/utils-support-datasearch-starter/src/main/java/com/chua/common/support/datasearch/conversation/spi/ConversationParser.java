package com.chua.common.support.datasearch.conversation.spi;

import com.chua.common.support.datasearch.conversation.ConversationMessage;
import reactor.core.publisher.Flux;

/**
* AI 会话消息解析器 SPI — 解析外部 AI 工具本地存储的聊天记录。
*
* <p>与 {@code UsageParser}（用量/计费）平行：本接口负责对话内容维度，
* 实现必须以惰性流式方式产出，禁止一次性全量装载进内存。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface ConversationParser {

    /**
    * 流式解析全部会话消息（响应式，支持背压）。
    *
    * @return 消息记录流
    */
    Flux<ConversationMessage> streamMessages();

    /**
    * 当前解析器标识（如 "claude-编码"、"qoder"）。
    *
    * @return SPI 名称
    */
    String name();
}
