package com.chua.deeplearning.support.llama;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalTextToAudioClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 llama.cpp (GGUF) 的本地文字转语音（TTS）客户端。
 * <p>
 * 调度 llama 引擎下已注册的语音合成模型（如 neutts-2e 等），
 * 统一以 {@link TextToAudioClient} 对外提供语音合成能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("llama")
public class LlamaTextToAudioClient extends AbstractLocalTextToAudioClient {

    /**
     * 构造 llama 语音合成客户端。
     *
     * @param setting 客户端配置
     */
    public LlamaTextToAudioClient(TextToAudioClientSetting setting) {
        super("llama", setting);
    }

    @Override
    /** Models */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, byte[].class);
    }
}
