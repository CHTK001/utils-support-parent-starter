package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalTextToAudioClient;

import java.util.List;

/**
   * safetensor 本地文字转语音（TTS）客户端（HTTP 网关）。
 * <p>
   * 通过本地 safetensor服务（localhost:8765）调度 TTS 模型（kokoro 等），
 * 统一以 {@link TextToAudioClient} 对外提供语音合成能力。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("safetensors")
public class SafeTensorTextToAudioClient extends AbstractLocalTextToAudioClient {

    /**
      * 构造 safetensor 语音合成客户端。
     *
     * @param setting 客户端配置
     */
    public SafeTensorTextToAudioClient(TextToAudioClientSetting setting) {
        super("safetensors", setting);
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return SafeTensorModels.ofType("tts");
    }
}
