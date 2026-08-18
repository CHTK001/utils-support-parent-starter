package com.chua.deeplearning.support.onnx;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.ai.client.AbstractLocalTextToAudioClient;
import com.chua.deeplearning.support.ai.client.DeeplearningModels;
import com.chua.deeplearning.support.onnx.audio.tts.MmsTtsTranslator;
import com.chua.deeplearning.support.onnx.audio.tts.PocketTtsTranslator;

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
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("onnx")
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
     * 内嵌的 MMS-TTS 合成器（懒加载）
     */
    private MmsTtsTranslator mmsTtsTranslator;

    /**
     * 内嵌的 Pocket-TTS 合成器（懒加载）
     */
    private PocketTtsTranslator pocketTtsTranslator;

    /**
     * 构造 ONNX 语音合成客户端。
     *
     * @param setting 客户端配置
     */
    public OnnxTextToAudioClient(TextToAudioClientSetting setting) {
        super("onnx", setting);
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
        // Pocket-TTS 走内嵌 ORT 合成器（Kyutai 流匹配 TTS，模型打包在 jar 中）
        if (POCKET_TTS_MODEL.equalsIgnoreCase(modelName) || modelName.toLowerCase().contains("pocket-tts")) {
            synchronized (this) {
                if (pocketTtsTranslator == null) {
                    pocketTtsTranslator = new PocketTtsTranslator();
                }
            }
            return pocketTtsTranslator.synthesize(this.text);
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
        super.close();
    }

    @Override
    public List<ModelDefinition> models() {
        return DeeplearningModels.models(engine, String.class, byte[].class);
    }
}
