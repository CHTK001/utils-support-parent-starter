import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.deeplearning.support.onnx.audio.AsrPipeline;
import com.chua.deeplearning.support.onnx.audio.VoiceprintPipeline;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 音频识别全流程综合测试报告生成器。
 */
public final class AudioFullTest {

    private AudioFullTest() {
    }

    /**
     * 主入口。
     *
     * @param args 无参数
     * @throws Exception 失败
     */
    public static void main(String[] args) throws Exception {
        int pass = 0;
        int total = 0;
        StringBuilder sb = new StringBuilder();

        // ===== 准备中文测试音频（TTS 合成） =====
        Path zhWav = Path.of(System.getProperty("java.io.tmpdir"), "zh-full-test.wav");
        if (!Files.exists(zhWav)) {
            try (TextToAudioClient tts = TextToAudioClient.create("onnx", "")) {
                tts.model("vits-icefall-zh");
                tts.voice("SSB0005");
                Files.write(zhWav, tts.synthesize("今天天气非常好，我们一起去公园散步吧。"));
            }
        }
        System.out.println("[TTS] 中文合成完成 -> " + zhWav);

        String enWav = "C:/Users/Administrator/AppData/Local/Temp/opencode/sensevoice/test_wavs/zh.wav";

        // ===== Test 1: paraformer-zh-small 中文识别 =====
        total++;
        try {
            long t0 = System.currentTimeMillis();
            String text = AsrPipeline.builder("paraformer-zh-small")
                    .postProcess(true).build().transcribe(zhWav);
            long ms = System.currentTimeMillis() - t0;
            boolean ok = text != null && !text.isBlank() && text.contains("天气");
            sb.append(String.format("| paraformer-zh-small | 中文 | %s | %dms | %s |\n", text, ms, ok ? "✅ PASS" : "❌ FAIL"));
            System.out.println("[1] paraformer zh: " + text + " (" + ms + "ms) => " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++;
        } catch (Exception e) {
            sb.append(String.format("| paraformer-zh-small | 中文 | EXCEPTION: %s | - | ❌ FAIL |\n", e.getMessage()));
            System.out.println("[1] paraformer EXCEPTION: " + e.getMessage());
        }

        // ===== Test 2: SenseVoice 中文识别 =====
        total++;
        try {
            long t0 = System.currentTimeMillis();
            String text = AsrPipeline.builder("sensevoice")
                    .language("zh").postProcess(true).build().transcribe(Path.of(enWav));
            long ms = System.currentTimeMillis() - t0;
            boolean ok = text != null && !text.isBlank() && text.contains("时间");
            sb.append(String.format("| sensevoice-small | 中文(官方测试) | %s | %dms | %s |\n", text, ms, ok ? "✅ PASS" : "❌ FAIL"));
            System.out.println("[2] SenseVoice zh: " + text + " (" + ms + "ms) => " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++;
        } catch (Exception e) {
            sb.append(String.format("| sensevoice-small | 中文(官方测试) | EXCEPTION: %s | - | ❌ FAIL |\n", e.getMessage()));
            System.out.println("[2] SenseVoice EXCEPTION: " + e.getMessage());
        }

        // ===== Test 3: whisper-tiny 英文识别 =====
        total++;
        try {
            Path enWavPath = Path.of("C:/Users/Administrator/AppData/Local/Temp/opencode/sherpa-onnx-moonshine-tiny-en-int8/test_wavs/0.wav");
            long t0 = System.currentTimeMillis();
            String text = AsrPipeline.builder("whisper-tiny")
                    .language("en").postProcess(true).build().transcribe(enWavPath);
            long ms = System.currentTimeMillis() - t0;
            boolean ok = text != null && !text.isBlank() && text.toLowerCase().contains("night");
            sb.append(String.format("| whisper-tiny | 英文 | %s | %dms | %s |\n", text, ms, ok ? "✅ PASS" : "❌ FAIL"));
            System.out.println("[3] whisper en: " + text + " (" + ms + "ms) => " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++;
        } catch (Exception e) {
            sb.append(String.format("| whisper-tiny | 英文 | EXCEPTION: %s | - | ❌ FAIL |\n", e.getMessage()));
            System.out.println("[3] whisper EXCEPTION: " + e.getMessage());
        }

        // ===== Test 4: paraformer + DFSMN降噪 + VAD 全管线 =====
        total++;
        try {
            long t0 = System.currentTimeMillis();
            String text = AsrPipeline.builder("paraformer-zh-small")
                    .vad(true).denoise(true).postProcess(true)
                    .build().transcribe(zhWav);
            long ms = System.currentTimeMillis() - t0;
            boolean ok = text != null && text.contains("天气");
            sb.append(String.format("| paraformer + 降噪 + VAD | 中文全管线 | %s | %dms | %s |\n", text, ms, ok ? "✅ PASS" : "❌ FAIL"));
            System.out.println("[4] full pipeline: " + text + " (" + ms + "ms) => " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++;
        } catch (Exception e) {
            sb.append(String.format("| paraformer 全管线 | 中文 | EXCEPTION: %s | - | ❌ FAIL |\n", e.getMessage()));
            System.out.println("[4] full pipeline EXCEPTION: " + e.getMessage());
        }

        // ===== Test 5: 声纹入库+检索 =====
        total++;
        try {
            VoiceprintPipeline vp = VoiceprintPipeline.create();
            vp.enroll("test-speaker", zhWav);
            var hits = vp.search(zhWav, 1);
            boolean ok = !hits.isEmpty() && hits.get(0).similarity() > 0.8;
            sb.append(String.format("| CAM++ 声纹 | 入库+检索 | top=%s sim=%.4f | - | %s |\n",
                    hits.isEmpty() ? "?" : hits.get(0).speakerId(),
                    hits.isEmpty() ? 0 : hits.get(0).similarity(),
                    ok ? "✅ PASS" : "❌ FAIL"));
            System.out.println("[5] voiceprint: " + (hits.isEmpty() ? "empty" :
                    hits.get(0).speakerId() + " " + String.format("%.4f", hits.get(0).similarity()))
                    + " => " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++;
        } catch (Exception e) {
            sb.append(String.format("| CAM++ 声纹 | 入库+检索 | EXCEPTION: %s | - | ❌ FAIL |\n", e.getMessage()));
            System.out.println("[5] voiceprint EXCEPTION: " + e.getMessage());
        }

        System.out.println("\n==== " + pass + "/" + total + " PASS ====");

        // Write markdown table for HTML generation
        Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "audio_test_results.txt"),
                String.valueOf(pass) + "/" + total);
    }
}

