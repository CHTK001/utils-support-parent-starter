package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.audio.TextToAudioClient;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 语音合成（TTS）能力 SPI 示例。
 *
 * <p>通过 {@link TextToAudioClient#create(String, String)} 切换提供商（onnx/llama），
 * 通过 {@code .model(modelId)} 切换模型（如 mms-tts-eng）。</p>
 *
 * <pre>{@code
 *   TextToAudioClientExample list
 *   TextToAudioClientExample onnx mms-tts-eng "Hello world"
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class TextToAudioClientExample extends ExampleBase {

    private TextToAudioClientExample() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printModels("tts", "onnx", TextToAudioClient.create("onnx", "").models());
            printModels("tts", "llama", TextToAudioClient.create("llama", "").models());
            return;
        }
        String provider = args[0];
        String model = args.length > 1 ? args[1] : null;
        String text = args.length > 2 ? args[2] : "Hello, this is a text to speech test.";

        TextToAudioClient client = TextToAudioClient.create(provider, "");
        if (model == null) {
            printModels("tts", provider, client.models());
            client.close();
            return;
        }
        client.model(model);
        long t0 = System.currentTimeMillis();
        byte[] wav = client.synthesize(text);
        System.out.println("[tts] text: " + text);
        System.out.println("       WAV: " + wav.length + " bytes");
        Path out = Files.createTempFile("tts-example-", ".wav");
        Files.write(out, wav);
        System.out.println("       saved: " + out);
        printResult("tts", provider, model, t0);
        client.close();
    }
}
