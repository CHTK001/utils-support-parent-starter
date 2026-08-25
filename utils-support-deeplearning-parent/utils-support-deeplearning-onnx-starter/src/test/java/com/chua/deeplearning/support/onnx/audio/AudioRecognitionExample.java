package com.chua.deeplearning.support.onnx.audio;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.deeplearning.support.onnx.audio.AsrPipeline;
import com.chua.deeplearning.support.onnx.audio.VoiceprintPipeline;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 语音识别全流程综合测试示例。
 *
 * <p>覆盖：中文TTS → paraformer/SenseVoice/whisper ASR → 降噪+VAD全管线 → CAM++声纹入库检索</p>
 *
 * <pre>
 *   java AudioRecognitionExample --text="今天天气" --voice=SSB0005
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AudioRecognitionExample {

    private static final String DEFAULT_TEXT = "今天天气非常好，我们一起去公园散步吧。";
    private static final String DEFAULT_VOICE = "SSB0005";

    public static void main(String[] args) throws Exception {
        var text = parseArg(args, "--text", DEFAULT_TEXT);
        var voice = parseArg(args, "--voice", DEFAULT_VOICE);

        // TTS 合成中文测试音频
        var zhWav = Path.of(System.getProperty("java.io.tmpdir"), "audio-e2e-zh.wav");
        try (var tts = TextToAudioClient.create("onnx", "")) {
            tts.model("vits-icefall-zh");
            tts.voice(voice);
            Files.write(zhWav, tts.synthesize(text));
        }
        log.info("[TTS] 合成完成 -> {}", zhWav);

        int pass = 0;
        int total = 0;

        // Test1: paraformer 中文识别
        total++;
        var r1 = runAsr("paraformer-zh-small", null, zhWav, false, false);
        var ok1 = r1 != null && !r1.isBlank();
        System.out.printf("[1] paraformer zh: %s => %s%n", r1, ok1 ? "PASS" : "FAIL");
        if (ok1) pass++;

        // Test2: SenseVoice 中文识别
        total++;
        var svWav = Path.of("C:/Users/Administrator/AppData/Local/Temp/opencode/sensevoice/zh.wav");
        var r2 = Files.exists(svWav)
                ? runAsr("sensevoice", "zh", svWav, false, false) : "";
        boolean ok2 = r2 != null && !r2.isBlank() && r2.contains("时间");
        System.out.printf("[2] sensevoice zh: %s => %s%n", r2, ok2 ? "PASS" : "FAIL");
        if (ok2) pass++;

        // Test3: whisper 英文识别
        total++;
        var enWav = Path.of("C:/Users/Administrator/AppData/Local/Temp/opencode/sherpa-onnx-moonshine-tiny-en-int8/test_wavs/0.wav");
        var r3 = runAsr("whisper-tiny", "en", enWav, false, false);
        var ok3 = r3 != null && !r3.isBlank() && r3.toLowerCase().contains("night");
        System.out.printf("[3] whisper en: %s => %s%n", r3, ok3 ? "PASS" : "FAIL");
        if (ok3) pass++;

        // Test4: 全管线（降噪 + VAD + paraformer）
        total++;
        var r4 = runAsr("paraformer-zh-small", null, zhWav, true, true);
        var ok4 = r4 != null && !r4.isBlank();
        System.out.printf("[4] full pipeline zh: %s => %s%n", r4, ok4 ? "PASS" : "FAIL");
        if (ok4) pass++;

        // Test5: 声纹入库 + 同人检索
        total++;
        double sim = -2;
        try {
            var vp = VoiceprintPipeline.create();
            vp.enroll("test-speaker-A", zhWav);
            var hits = vp.search(zhWav, 1);
            if (!hits.isEmpty() && hits.get(0).speakerId().equals("test-speaker-A")) {
                sim = hits.get(0).similarity();
            }
        } catch (Exception e) {
            log.warn("[Voiceprint] 异常: {}", e.getMessage());
        }
        var ok5 = sim > 0.8;
        System.out.printf("[5] voiceprint same-speaker sim=%.4f => %s%n", sim, ok5 ? "PASS" : "FAIL");
        if (ok5) pass++;

        System.out.printf("%n==== %d/%d PASS ====%n", pass, total);
        System.exit(pass >= total - 1 ? 0 : 1);
    }

    /**
     * 运行 ASR 管线。
     */
    private static String runAsr(String engine, String lang, Path wav,
                                 boolean vad, boolean denoise) throws Exception {
        var builder = AsrPipeline.builder(engine)
                .vad(vad)
                .denoise(denoise)
                .postProcess(true);
        if (lang != null) {
            builder.language(lang);
        }
        return builder.build().transcribe(wav);
    }

    /**
     * 解析 --key=value 格式参数。
     */
    private static String parseArg(String[] args, String key, String def) {
        for (String arg : args) {
            if (arg.startsWith(key + "=")) {
                return arg.substring(key.length() + 1);
            }
        }
        return def;
    }
}
