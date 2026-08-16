package com.chua.deeplearning.support.llama;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalChatClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 llama.cpp (GGUF) 的本地文本对话客户端。
 * <p>
 * 调度 llama 引擎下已注册的文本生成模型（如 minicpm5、gemma-4-e2b 等），
 * 统一以 {@link ChatClient} 对外提供对话能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("llama")
public class LlamaChatClient extends AbstractLocalChatClient {

    /**
     * 构造 llama 对话客户端。
     *
     * @param setting 客户端配置
     */
    public LlamaChatClient(ChatClientSetting setting) {
        super("llama", setting);
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, String.class);
    }
}
