package com.chua.deeplearning.support.onnx.audio.moss;

import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.audio.TextToAudioResponse;
import com.chua.common.support.spi.annotations.Spi;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MOSS-TTS-Nano 多语言 TTS 标准客户端（48 kHz）。
 *
 * <p>通过 SPI 名称 {@code moss-tts-nano} 创建：
 *
 * <pre>{@code
 * TextToAudioClient client = TextToAudioClient.create("onnx", "moss-tts-nano");
 * byte[] wav = client.voice("Junhao").synthesize("你好世界");
 * }</pre>
 *
 * <p>模型（TTS 640MB + Codec 85MB）首次使用时自动从 hf-mirror 下载到缓存目录，
 * 也可通过系统属性 {@code speech.loop.moss.dir} / {@code speech.loop.codec.dir}
 * 指定已有模型目录。
 *
 * @author chua
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"moss-tts-nano", "moss-tts"})
public class MossTextToAudioClient implements TextToAudioClient {

    private static final String TTS_MIRROR =
            "https://hf-mirror.com/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/resolve/main/";
    private static final String CODEC_MIRROR =
            "https://hf-mirror.com/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/resolve/main/";

    private static final String[] TTS_FILES = {
            "moss_tts_prefill.onnx",
            "moss_tts_global_shared.data",
            "moss_tts_decode_step.onnx",
            "moss_tts_local_fixed_sampled_frame.onnx",
            "moss_tts_local_shared.data",
            "tokenizer.model",
            "browser_poc_manifest.json",
    };

    private static final String[] CODEC_FILES = {
            "moss_audio_tokenizer_decode_full.onnx",
            "moss_audio_tokenizer_decode_shared.data",
    };

    private final TextToAudioClientSetting setting;
    private MossTtsTranslator translator;
    private boolean prepared;

    /**
     * 克隆参考音频（设置后优先于内置音色）。
     */
    private java.nio.file.Path referencePath;

    /**
     * 构造客户端。
     *
     * @param setting 配置
     */
    public MossTextToAudioClient(TextToAudioClientSetting setting) {
        this.setting = setting;
    }

    @Override
    public TextToAudioClient model(String model) {
        return this;
    }

    @Override
    public TextToAudioClient voice(String voice) {
        setting.setVoice(voice);
        return this;
    }

    @Override
    public TextToAudioClient language(String language) {
        return this;
    }

    @Override
    public TextToAudioClient format(String format) {
        return this;
    }

    @Override
    public TextToAudioClient speed(Double speed) {
        return this;
    }

    @Override
    public TextToAudioClient sampleRate(Integer sampleRate) {
        return this;
    }

    @Override
    public TextToAudioClient temperature(Double temperature) {
        return this;
    }

    @Override
    public TextToAudioClient seed(Long seed) {
        return this;
    }

    @Override
    public TextToAudioClient text(String text) {
        setting.setText(text);
        return this;
    }

    /**
     * 设置克隆参考音频（优先于 voice）。
     *
     * @param refPath 参考音频 WAV 路径（建议 5~10 秒干净人声）
     * @return this
     */
    public MossTextToAudioClient reference(java.nio.file.Path refPath) {
        this.referencePath = refPath;
        return this;
    }

    /**
     * 设置克隆参考音频字节。
     *
     * @param wavBytes 参考音频 WAV 字节
     * @return this
     */
    public MossTextToAudioClient reference(byte[] wavBytes) throws IOException {
        Path temp = Files.createTempFile("moss-ref-", ".wav");
        Files.write(temp, wavBytes);
        temp.toFile().deleteOnExit();
        this.referencePath = temp;
        return this;
    }

    @Override
    public byte[] synthesize(String text) {
        String target = text != null ? text : setting.getText();
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("No text configured");
        }
        ensurePrepared();
        try {
            long t0 = System.currentTimeMillis();
            byte[] wav;
            if (referencePath != null) {
                wav = translator.synthesizeWithReference(target, referencePath, 80);
            } else {
                String voice = setting.getVoice() != null ? setting.getVoice() : "Junhao";
                wav = translator.synthesizeText(target, voice);
            }
            log.info("[MossTTS] synthesize {}ms, {} bytes",
                    System.currentTimeMillis() - t0, wav.length);
            return wav;
        } catch (Exception e) {
            throw new RuntimeException("MossTTS synthesize failed", e);
        }
    }

    @Override
    public String createTask(String text) {
        return "moss-tts-" + UUID.randomUUID();
    }

    @Override
    public TextToAudioResponse queryTask(String taskId) {
        try {
            byte[] audio = synthesize(setting.getText());
            return TextToAudioResponse.builder()
                    .taskId(taskId)
                    .status(TextToAudioResponse.Status.SUCCESS)
                    .audioBytes(audio)
                    .format("wav")
                    .sampleRate(48000)
                    .voice(setting.getVoice())
                    .build();
        } catch (Exception e) {
            log.error("[MossTTS] queryTask failed: {}", e.getMessage(), e);
            return TextToAudioResponse.builder()
                    .taskId(taskId)
                    .status(TextToAudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /** 确保模型目录就绪：优先系统属性指定目录，否则缓存目录缺失时自动下载。 */
    private void ensurePrepared() {
        if (prepared) {
            return;
        }
        try {
            Path ttsDir = ttsDir();
            Path codecDir = codecDir();
            downloadMissing(ttsDir, TTS_FILES, TTS_MIRROR);
            downloadMissing(codecDir, CODEC_FILES, CODEC_MIRROR);

            translator = new MossTtsTranslator();
            translator.prepare(ttsDir, codecDir);
            prepared = true;
        } catch (Exception e) {
            throw new RuntimeException("MossTTS model prepare failed", e);
        }
    }

    private Path ttsDir() throws IOException {
        String prop = System.getProperty("speech.loop.moss.dir");
        Path dir = (prop != null && !prop.isBlank())
                ? Path.of(prop.trim())
                : Path.of(cacheRoot(), "audio", "tts", "moss-tts-nano");
        Files.createDirectories(dir);
        return dir;
    }

    private Path codecDir() throws IOException {
        String prop = System.getProperty("speech.loop.codec.dir");
        Path dir = (prop != null && !prop.isBlank())
                ? Path.of(prop.trim())
                : Path.of(cacheRoot(), "audio", "tts", "moss-audio-tokenizer");
        Files.createDirectories(dir);
        return dir;
    }

    private void downloadMissing(Path dir, String[] files, String mirrorBase) throws IOException {
        Files.createDirectories(dir);
        for (String name : files) {
            Path target = dir.resolve(name);
            if (Files.exists(target) && Files.size(target) > 1024) {
                continue;
            }
            log.info("[MossTTS] downloading {}...", name);
            Path temp = dir.resolve(name + ".part");
            try (InputStream in = URI.create(mirrorBase + name).toURL().openStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String cacheRoot() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        return (prop != null && !prop.isBlank()) ? prop.trim() : System.getProperty("java.io.tmpdir");
    }

    @Override
    public void close() {
        if (translator != null) {
            translator.close();
            translator = null;
        }
        prepared = false;
    }
}
