package com.chua.deeplearning.support.gpu_llama3;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalChatClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 llama.cpp (GGUF) GPU 加速的本地文本对话客户端。
 * <p>
 * 调度 GPU 引擎下已注册的 Llama 3 模型，统一以 {@link ChatClient} 对外提供对话能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("gpu-llama3")
public class GpuLlama3ChatClient extends AbstractLocalChatClient {

    /**
     * 构造 GPU Llama3 对话客户端。
     *
     * @param setting 客户端配置
     */
    public GpuLlama3ChatClient(ChatClientSetting setting) {
        super("gpu-llama3", setting);
    }

    @Override
    /**
     * 模型
    */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, String.class);
    }
}
