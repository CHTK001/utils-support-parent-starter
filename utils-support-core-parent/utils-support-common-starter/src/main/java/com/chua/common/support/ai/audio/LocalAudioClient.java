package com.chua.common.support.ai.audio;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地 ASR 桩客户端。
 *
 * <p>返回固定文案用于集成测试与接口验证，不进行真实转写。
 * 真实端到端请使用 {@link com.chua.deeplearning.support.onnx.audio.whisper.WhisperAudioClient}。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("local")
public class LocalAudioClient implements AudioClient {

    @Override
    public String transcribe(Path path) {
        if (path != null && Files.exists(path)) {
            return "【本地桩】已收到音频: " + path.getFileName() + "，请接入真实 ASR 引擎。";
        }
        return "【本地桩】音频为空，请配置真实 ASR provider。";
    }

    @Override
    public String createTask(Path path) {
        return "local-stub-" + System.currentTimeMillis();
    }

    @Override
    public AudioResponse queryTask(String taskId) {
        return AudioResponse.builder()
                .taskId(taskId)
                .status(AudioResponse.Status.SUCCESS)
                .transcript("【本地桩】同步返回，按同步结果处理。")
                .build();
    }
}
