package com.chua.deeplearning.support.safetensors;

import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalAudioClient;

import java.util.List;

/**
* safetensor 本地语音识别（ASR）客户端（HTTP 网关）。
* <p>
* 通过本地 safetensor服务（localhost:8765）调度 ASR 模型（whisper、paraformer 等），
* 统一以 {@link VirtualClient} 对外提供语音转写能力。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("safetensors")
public class SafeTensorAudioClient extends AbstractLocalAudioClient {

    /**
    * 构造 safetensor 语音识别客户端。
    *
    * @param setting 客户端配置
     */
    public SafeTensorAudioClient(AudioClientSetting setting) {
        super("safetensors", setting);
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return SafeTensorModels.ofType("asr");
    }
}

