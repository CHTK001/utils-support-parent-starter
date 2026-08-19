package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.audio.AudioClient;

import java.nio.file.Path;

/**
 * 语音识别（ASR）能力 SPI 示例。
 *
 * <p>通过 {@link AudioClient#create(String, String)} 切换提供商（onnx/whisper），
 * 通过 {@code .model(modelId)} 切换模型。</p>
 *
 * <pre>{@code
 *   AudioClientExample list
 *   AudioClientExample whisper whisper-tiny audio.wav
 *   AudioClientExample onnx whisper-tiny audio.wav
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class AudioClientExample extends ExampleBase {

    /** 创建 AudioClientExample 实例 */
    private AudioClientExample() {
    }

    /** Main */
    public static void main(String[] args) {
        if (args.length == 0) {
            printModels("audio", "whisper", AudioClient.create("whisper", "").models());
            printModels("audio", "onnx", AudioClient.create("onnx", "").models());
            return;
        }
        String provider = args[0];
        String model = args.length > 1 ? args[1] : null;
        String audioPath = args.length > 2 ? args[2] : null;

        AudioClient client = AudioClient.create(provider, "");
        if (model == null) {
            printModels("audio", provider, client.models());
            client.close();
            return;
        }
        client.model(model);
        if (audioPath == null) {
            System.out.println("[audio] 需要音频文件路径");
            client.close();
            return;
        }
        long t0 = System.currentTimeMillis();
        String text = client.transcribe(Path.of(audioPath));
        System.out.println("[audio] file: " + audioPath);
        System.out.println("       text: " + text);
        printResult("audio", provider, model, t0);
        client.close();
    }
}
