package com.chua.deeplearning.support.onnx.audio.zipformer;

import com.chua.common.support.ai.audio.AudioClient;
import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Zipformer 纯中文流式 ASR 客户端。
 *
 * <p>基于 sherpa-onnx-streaming-zipformer-zh-14M，支持 chunk-by-chunk 实时转写。
 * 模型首次使用时自动从 HF 下载 (~15MB)，缓存到 %TEMP%/audio/asr/zipformer-zh/。
 *
 * <p>Provider 名称：{@code zipformer-zh} / {@code zipformer-zh-streaming}
 *
 * <pre>{@code
 * AudioClient client = AudioClient.create("zipformer-zh", "");
 * String text = client.transcribe(Path.of("audio.wav"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
//@Spi({"zipformer-zh", "zipformer-zh-streaming"})
public class ZipformerZhAudioClient implements AudioClient {

    private static final String HF_BASE =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M/resolve/main/";

    private static final String[] MODEL_FILES = {
            "encoder-epoch-99-avg-1.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.onnx",
            "tokens.txt",
    };

    private static final String CACHE_SUBDIR = "audio/asr/zipformer-zh/";
    private static final String TMP_PREFIX = "zipformer-zh-audio-";

    private final AudioClientSetting setting;
    private ZipformerStreamingTranslator translator;
    private boolean prepared;

    public ZipformerZhAudioClient(AudioClientSetting setting) {
        this.setting = setting;
    }

    @Override public AudioClient model(String m) { return this; }
    @Override public AudioClient language(String lang) { return this; }
    @Override public AudioClient sampleRate(Integer sr) { return this; }
    @Override public AudioClient format(String fmt) { return this; }
    @Override public AudioClient prompt(String p) { return this; }
    @Override public AudioClient temperature(Double t) { return this; }
    @Override public AudioClient seed(Long s) { return this; }
    @Override public AudioClient audio(byte[] a) { setting.setAudio(a); return this; }
    @Override public AudioClient audio(InputStream in) { setting.setAudioInput(in); return this; }
    @Override public AudioClient audio(Path p) { setting.setAudioPath(p); return this; }

    @Override
    public String transcribe(Path path) {
        ensurePrepared();
        try {
            Path target = path != null ? path : resolveAudioPath();
            long t0 = System.currentTimeMillis();
            String text = translator.transcribe(target);
            log.info("[ZipformerZh] transcribe {}ms: {}", System.currentTimeMillis() - t0, text);
            return text;
        } catch (Exception e) {
            throw new RuntimeException("ZipformerZh transcribe failed", e);
        }
    }

    @Override
    public String createTask(Path path) {
        return "zipformer-zh-" + UUID.randomUUID();
    }

    @Override
    public AudioResponse queryTask(String taskId) {
        try {
            ensurePrepared();
            String text = transcribe(resolveAudioPath());
            return AudioResponse.builder()
                    .taskId(taskId).status(AudioResponse.Status.SUCCESS)
                    .transcript(text).build();
        } catch (Exception e) {
            log.error("[ZipformerZh] queryTask failed: {}", e.getMessage(), e);
            return AudioResponse.builder()
                    .taskId(taskId).status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage()).build();
        }
    }

    @Override
    public List<com.chua.common.support.ai.chat.ModelDefinition> models() {
        return List.of(
                com.chua.common.support.ai.chat.ModelDefinition.builder()
                        .id("zipformer-zh-14m").name("Zipformer-zh-14M")
                        .description("纯中文流式 ASR，~15MB int8")
                        .build()
        );
    }

    private void ensurePrepared() {
        if (prepared) return;
        synchronized (this) {
            if (prepared) return;
            try {
                Path modelDir = modelDir();
                if (!Files.exists(modelDir.resolve(MODEL_FILES[0]))) {
                    downloadModels(modelDir);
                }
                translator = new ZipformerStreamingTranslator();
                translator.prepare(modelDir);
                prepared = true;
                log.info("[ZipformerZh] model loaded: {}", modelDir);
            } catch (Exception e) {
                throw new RuntimeException("ZipformerZh model prepare failed", e);
            }
        }
    }

    private Path modelDir() throws IOException {
        String prop = System.getProperty("speech.loop.zipformer-zh.dir");
        if (prop != null && !prop.isBlank()) return Path.of(prop.trim());
        Path dir = Path.of(cacheRoot(), "audio/asr/zipformer-zh");
        Files.createDirectories(dir);
        return dir;
    }

    private void downloadModels(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (String name : MODEL_FILES) {
            Path target = dir.resolve(name);
            if (Files.exists(target) && Files.size(target) > 1024) continue;
            log.info("[ZipformerZh] downloading {}...", name);
            Path tmp = dir.resolve(name + ".part");
            try (InputStream in = java.net.URI.create(HF_BASE + name).toURL().openStream()) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[ZipformerZh] downloaded {} ({}) bytes", name, Files.size(target));
        }
    }

    private Path resolveAudioPath() {
        if (setting.getAudioPath() != null) return setting.getAudioPath();
        if (setting.getAudio() == null && setting.getAudioInput() == null)
            throw new IllegalStateException("No audio input configured");
        try {
            Path tmp = Files.createTempFile(TMP_PREFIX, ".wav");
            if (setting.getAudio() != null) Files.write(tmp, setting.getAudio());
            else {
                InputStream in = setting.getAudioInput();
                if (in != null) Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (IOException e) {
            throw new RuntimeException("Failed to materialize audio", e);
        }
    }

    private static String cacheRoot() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        return (prop != null && !prop.isBlank()) ? prop.trim() : System.getProperty("java.io.tmpdir");
    }

    @Override public void close() {
        if (translator != null) { try { translator.close(); } catch (Exception ignore) {} }
        prepared = false;
    }
}
