package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.audio.AudioClient;
import com.chua.common.support.ai.audio.TextToAudioClient;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * STT→TTS 语音交互管线示例。
 *
 * <p>完整流程：音频文件 → Whisper ASR 转写 → 文本 → VITS-TTS 合成语音。</p>
 *
 * <pre>{@code
 *   // 列出可用模型
 *   SttTtsPipelineExample list
 *
 *   // 完整管线：音频 → 文本 → 合成 WAV
 *   SttTtsPipelineExample whisper-tiny vits-icefall-zh input.wav SSB0005
 *
 *   // 仅 STT
 *   SttTtsPipelineExample whisper-tiny null input.wav
 *
 *   // 仅 TTS（使用上一步输出的文本）
 *   SttTtsPipelineExample null vits-icefall-zh null SSB0005 "你好世界"
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class SttTtsPipelineExample extends ExampleBase {

    private SttTtsPipelineExample() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printModels("stt", "whisper", AudioClient.create("whisper", "").models());
            printModels("tts", "onnx", TextToAudioClient.create("onnx", "").models());
            return;
        }

        String sttProvider = args[0];
        String ttsModel = args.length > 1 ? args[1] : null;
        String audioPath = args.length > 2 ? args[2] : null;
        String voice = args.length > 3 ? args[3] : "0";
        String overrideText = args.length > 4 ? args[4] : null;

        // Step 1: STT — 音频 → 文本
        String transcript = null;
        if (!"null".equalsIgnoreCase(sttProvider) && audioPath != null) {
            try (AudioClient sttClient = AudioClient.create(sttProvider, "")) {
                sttClient.model(sttProvider.startsWith("whisper") ? "whisper-tiny" : sttProvider);
                if ("paraformer".equals(sttProvider)) {
                    sttClient.model("paraformer-zh-small");
                }
                long t0 = System.currentTimeMillis();
                transcript = sttClient.transcribe(Path.of(audioPath));
                System.out.println("[STT]  音频: " + audioPath);
                System.out.println("      耗时: " + (System.currentTimeMillis() - t0) + "ms");
                System.out.println("      文本: " + transcript);
            }
        }

        // 如果指定了 overrideText，则直接使用
        String textToSynthesize = overrideText != null && !overrideText.isBlank()
                ? overrideText
                : (transcript != null && !transcript.isBlank() ? transcript : "Hello world, this is a test.");

        // Step 2: TTS — 文本 → 音频
        if (!"null".equalsIgnoreCase(ttsModel)) {
            try (TextToAudioClient ttsClient = TextToAudioClient.create("onnx", "")) {
                ttsClient.model(ttsModel);
                ttsClient.voice(voice);
                long t0 = System.currentTimeMillis();
                byte[] wav = ttsClient.synthesize(textToSynthesize);
                Path out = Files.createTempFile("stt-tts-pipeline-", ".wav");
                Files.write(out, wav);
                System.out.println("[TTS]  模型: " + ttsModel);
                System.out.println("      说话人: " + voice);
                System.out.println("      耗时: " + (System.currentTimeMillis() - t0) + "ms");
                System.out.println("      WAV:  " + wav.length + " bytes -> " + out);
                printResult("tts", "onnx", ttsModel, t0);
            }
        }
    }
}
