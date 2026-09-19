package com.chua.common.support.ai.chat;

import com.chua.common.support.spi.annotations.Spi;

/**
 * 内存对话客户端，用于测试和演示。
 * <p>
 * 返回固定格式的回答，无需外部服务。
 * 仅用于功能验证，不适合生产环境。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("memory")
public class MemoryChatClient implements ChatClient {

    @Override
    /**
     * ChatSync
    */
    public String chatSync(String prompt) {
        return "【内存模式】这是对问题的模拟回答。实际使用时请配置真实的 LLM 服务。\n问题: " + prompt;
    }
}
