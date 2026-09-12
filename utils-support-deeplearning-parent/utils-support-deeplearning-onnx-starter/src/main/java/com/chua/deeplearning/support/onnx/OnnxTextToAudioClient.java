package com.chua.deeplearning.support.onnx;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalTextToAudioClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;
import com.chua.deeplearning.support.onnx.audio.tts.MmsTtsTranslator;
import com.chua.deeplearning.support.onnx.audio.tts.PocketTtsTranslator;
import com.chua.deeplearning.support.onnx.audio.tts.VitsTtsTranslator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 基于 ONNX Runtime 的本地文字转语音（TTS）客户端。
 * <p>
 * 调度 onnx 引擎下已注册的语音合成模型（如 mms-tts-eng、piper 等），
 * 统一以 {@link TextToAudioClient} 对外提供语音合成能力。
 * </p>
 *
 * <p>用法：
 * <pre>{@code
 *   byte[] wav = TextToAudioClient.create("onnx", "")
 *       .model("mms-tts-eng")
 *       .synthesize("Hello world");
 * }</pre>size("Hello world");
 * }</pre>
 *
 * <p>VITS 多说话人：
 * <pre>{@code
 *   TextToAudioClient.create("onnx", "")
 *       .model("vits-icefall-zh")
 *       .voice("SSB0005")     // 说话人名称
 *       .synthesize("你好世界");
 * }</pre>.synthesize("你好世界");
 * }</pre>
 *
 * <p>Pocket-TTS 声音克隆（参考音频路径）：
 * <pre>{@code
 *   TextToAudioClient.create("onnx", "")
 *       .model("pocket-tts")
 *       .voice("ref_audio.wav") // 引用音频文件路径
 *       .synthesize("Hello world");
 * }</pre>.synthesize("Hello world");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"onnx", "vits", "vits-icefall-zh", "pocket-tts", "mms-tts"})
@Slf4j
public class OnnxTextToAudioClient extends AbstractLocalTextToAudioClient {

    /**
     * MMS-TTS 模型名
     */
    private static final String MMS_TTS_MODEL = "mms-tts-eng";

    /**
     * Pocket-TTS 模型名（Kyutai 流匹配 TTS）
     */
    private static final String POCKET_TTS_MODEL = "pocket-tts";

    /**
     * VITS-icefall 中文 TTS 模型名（AISHELL3 多说话人）
     */
    private static final String VITS_ICEEFALL_ZH_MODEL = "vits-icefall-zh";

    /**
     * 内嵌的 MMS-TTS 合成器（懒加载）
     */
    private MmsTtsTranslator mmsTtsTranslator;

    /**
     * 内嵌的 Pocket-TTS 合成器（懒加载）
     */
    private PocketTtsTranslator pocketTtsTranslator;

    /**
     * 内嵌的 VITS-icefall 中文合成器（懒加载）
     */
    private VitsTtsTranslator vitsTtsTranslator;

    /**
     * 说话人指定（voice 参数）。
      * VITS 模型：说话人名称或 标识（如 "SSB0005" 或 "0"）。
     * Pocket-TTS：参考音频文件路径（用于零样本声音克隆）。
     */
    private String voice;

    /**
     * 构造 ONNX 语音合成客户端。
     *
     * @param setting 客户端配置
     */
    public OnnxTextToAudioClient(TextToAudioClientSetting setting) {
        super("onnx", setting);
        this.voice = setting != null ? setting.getVoice() : null;
    }

    @Override
    public TextToAudioClient voice(String voice) {
        this.voice = voice;
        return this;
    }

    /**
     * 获取 VITS 说话人名称列表（供前端下拉选项使用）。
     *
     * @return 说话人名称列表
     */
    public List<String> getVitsSpeakerNames() {
        ensureVits();
        return vitsTtsTranslator.speakerNames();
    }

    /**
     * 确保 VITS translator 已初始化。
     */
    private void ensureVits() {
        synchronized (this) {
            if (vitsTtsTranslator == null) {
                vitsTtsTranslator = new VitsTtsTranslator();
            }
        }
    }

    /**
      * 解析 VITS 说话人 标识：voice 参数为数字时直接使用，否则按说话人名查 speakers 序。
     * 无法解析回退 0。
     *
     * @return 说话人 标识
     */
    private int resolveSpeakerId() {
        if (voice != null && !voice.isBlank()) {
            try {
                return Integer.parseInt(voice.trim());
            } catch (NumberFormatException ignored) {
                ensureVits();
                List<String> names = vitsTtsTranslator.speakerNames();
                int idx = names.indexOf(voice.trim());
                if (idx >= 0) {
                    return idx;
                }
                log.warn("[VITS] 未知说话人 {}，回退 speaker=0", voice);
            }
        }
        return 0;
    }

    /**
     * 加载参考音频字节（Pocket-TTS 声音克隆用）。
     *
     * @return 参考音频 WAV 字节；voice 为空或非文件路径时返回 空
     */
    private byte[] loadRefAudio() {
        if (voice == null || voice.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(voice.trim());
            if (Files.exists(p) && Files.isRegularFile(p)) {
                return Files.readAllBytes(p);
            }
        } catch (IOException e) {
            log.warn("[PocketTTS] 读取参考音频失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public byte[] synthesize(String text) {
        if (text != null) {
            this.text = text;
        }
        String modelName = resolveModel();
        // MMS-TTS 走内嵌 ORT 合成器（模型打包在 jar 中）
        if (MMS_TTS_MODEL.equalsIgnoreCase(modelName) || modelName.toLowerCase().contains("mms-tts")) {
            synchronized (this) {
                if (mmsTtsTranslator == null) {
                    mmsTtsTranslator = new MmsTtsTranslator();
                }
            }
            return mmsTtsTranslator.synthesize(this.text);
        }
        // Pocket-TTS 走内嵌 ORT 合成器（Kyutai 流匹配 TTS，支持声音克隆）
        if (POCKET_TTS_MODEL.equalsIgnoreCase(modelName) || modelName.toLowerCase().contains("pocket-tts")) {
            synchronized (this) {
                if (pocketTtsTranslator == null) {
                    pocketTtsTranslator = new PocketTtsTranslator();
                }
            }
            byte[] refAudio = loadRefAudio();
            return pocketTtsTranslator.voice(this.text, refAudio);
        }
        // VITS-icefall 中文 TTS 走内嵌 ORT 合成器（AISHELL3 多说话人，模型打包在 jar 中）
        if (VITS_ICEEFALL_ZH_MODEL.equalsIgnoreCase(modelName) || modelName.toLowerCase().contains("vits")) {
            synchronized (this) {
                if (vitsTtsTranslator == null) {
                    vitsTtsTranslator = new VitsTtsTranslator();
                }
            }
            return vitsTtsTranslator.synthesize(this.text, resolveSpeakerId());
        }
        return super.synthesize(this.text);
    }

    @Override
    public void close() {
        if (mmsTtsTranslator != null) {
            mmsTtsTranslator.close();
            mmsTtsTranslator = null;
        }
        if (pocketTtsTranslator != null) {
            pocketTtsTranslator.close();
            pocketTtsTranslator = null;
        }
        if (vitsTtsTranslator != null) {
            vitsTtsTranslator.close();
            vitsTtsTranslator = null;
        }
        super.close();
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, byte[].class);
    }
}
