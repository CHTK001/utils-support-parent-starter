package com.chua.deeplearning.support.translator;

import com.chua.common.support.ai.chat.ChatMessage;

import java.util.List;

/**
 * 多轮对话翻译器能力接口。
 *
 * <p>实现本接口的 {@link ITranslator} 可接收完整对话消息列表（system + 历史 + 本轮用户输入），
 * 由翻译器自行套用模型对话模板；未实现本接口的翻译器在本地对话客户端中回退为纯文本拼接。</p>
 *
 * <p>典型实现：llama-server sidecar 翻译器（OpenAI 兼容 messages 透传）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface ConversationTranslator {

    /**
     * 执行多轮对话翻译。
     *
     * @param messages 对话消息列表（system/assistant/user），按时间正序
     * @return 回答文本
     */
    String translate(List<ChatMessage> messages);
}
