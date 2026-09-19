package com.chua.deeplearning.support.onnx;

import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalAudioClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;

import java.util.List;

/**
 * 基于 ONNX Runtime 的本地语音识别（ASR）客户端。
 * <p>
 * 调度 onnx 引擎下已注册的语音识别模型（如 whisper、moonshine 等），
 * 统一以 {@link VirtualClient} 对外提供语音转写能力。
 * </p>
 *
 * <p>用法：
 * <pre>{@code
 *   String text = VirtualClient.create("onnx", "")
 *       .model("whisper-tiny")
 *       .language("zh")
 *       .transcribe(Path.of("audio.wav"));
 * }</pre>(Path.of("audio.wav"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("onnx")
public class OnnxAudioClient extends AbstractLocalAudioClient {

    /**
     * 构造 ONNX 语音识别客户端。
     *
     * @param setting 客户端配置
     */
    public OnnxAudioClient(AudioClientSetting setting) {
        super("onnx", setting);
    }

    @Override
    /** 模型 */
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, byte[].class, String.class);
    }
}

