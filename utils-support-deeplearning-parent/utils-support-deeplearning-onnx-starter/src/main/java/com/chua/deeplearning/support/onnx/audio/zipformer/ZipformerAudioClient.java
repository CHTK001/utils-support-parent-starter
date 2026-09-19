package com.chua.deeplearning.support.onnx.audio.zipformer;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;

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
 * Zipformer 中英双语流式 ASR 标准客户端。
 *
 * <p>通过 SPI 名称 {@code zipformer} / {@code zipformer-zh-en} 创建：
 *
 * <pre>{@code
 * VirtualClient client = VirtualClient.create("onnx", "zipformer");
 * String text = client.audio(Path.of("test.wav")).transcribe();
 * }</pre>
 *
 * <p>模型（int8 约 189MB）首次使用时自动从 hf-mirror 下载到缓存目录，
 * 也可通过系统属性 {@code speech.loop.zipformer.dir} 指定已有模型目录。
 *
 * @author chua
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"zipformer", "zipformer-zh-en", "zipformer-streaming"})
public class ZipformerAudioClient implements VirtualClient {

    private static final String HF_MIRROR =
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/";

    /**
     * 需要就位的模型文件名。
    */
    private static final String[] MODEL_FILES = {
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
    };

    private final AudioClientSetting setting;
    /**
     * translator
    */
    private ZipformerStreamingTranslator translator;
    /**
     * prepared
    */
    private boolean prepared;

    /**
     * 构造客户端。
     *
     * @param setting 配置
     */
    public ZipformerAudioClient(AudioClientSetting setting) {
        this.setting = setting;
    }

    @Override
    public VirtualClient model(String model) {
        return this;
    }

    @Override
    public VirtualClient language(String language) {
        return this;
    }

    @Override
    public VirtualClient sampleRate(Integer sampleRate) {
        return this;
    }

    @Override
    public VirtualClient format(String format) {
        return this;
    }

    @Override
    public VirtualClient prompt(String prompt) {
        return this;
    }

    @Override
    public VirtualClient temperature(Double temperature) {
        return this;
    }

    @Override
    public VirtualClient seed(Long seed) {
        return this;
    }

    @Override
    public VirtualClient audio(byte[] audio) {
        setting.setAudio(audio);
        return this;
    }

    @Override
    public VirtualClient audio(InputStream input) {
        setting.setAudioInput(input);
        return this;
    }

    @Override
    public VirtualClient audio(Path path) {
        setting.setAudioPath(path);
        return this;
    }

    @Override
    public String transcribe(Path path) {
        Path target = path != null ? path : resolveAudioPath();
        ensurePrepared();
        try {
            long t0 = System.currentTimeMillis();
            String text = translator.transcribe(target);
            log.info("[Zipformer] transcribe {}ms: {}", System.currentTimeMillis() - t0, text);
            return text;
        } catch (Exception e) {
            throw new RuntimeException("Zipformer transcribe failed", e);
        }
    }

    @Override
    public String createTask(Path path) {
        return "zipformer-" + UUID.randomUUID();
    }

    @Override
    public AudioResponse queryTask(String taskId) {
        try {
            String transcript = transcribe(resolveAudioPath());
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(transcript)
                    .build();
        } catch (Exception e) {
            log.error("[Zipformer] queryTask failed: {}", e.getMessage(), e);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 确保模型目录就绪：优先系统属性指定目录，否则缓存目录缺失时自动下载。
    */
    private void ensurePrepared() {
        if (prepared) {
            return;
        }
        try {
            Path modelDir = modelDir();
            if (!Files.exists(modelDir.resolve(MODEL_FILES[0]))) {
                downloadModels(modelDir);
            }
            translator = new ZipformerStreamingTranslator();
            translator.prepare(modelDir);
            prepared = true;
        } catch (Exception e) {
            throw new RuntimeException("Zipformer model prepare failed", e);
        }
    }

    /**
     * 模型目录。
     *
     * @return 路径 对象
     * @throws IOException 当执行过程不满足前置条件时
     */
    private Path modelDir() throws IOException {
        String prop = System.getProperty("speech.loop.zipformer.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop.trim());
        }
        Path dir = Path.of(cacheRoot(), "audio", "asr", "zipformer-zh-en");
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * downloadModels。
     *
     * @param dir 目录，不允许为 null
     * @throws IOException 当执行过程不满足前置条件时
     */
    private void downloadModels(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<String> failed = new ArrayList<>();
        for (String name : MODEL_FILES) {
            Path target = dir.resolve(name);
            if (Files.exists(target) && Files.size(target) > 1024) {
                continue;
            }
            log.info("[Zipformer] downloading {}...", name);
            Path temp = dir.resolve(name + ".part");
            try (InputStream in = URI.create(HF_MIRROR + name).toURL().openStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!failed.isEmpty()) {
            throw new IOException("模型文件下载失败: " + failed);
        }
    }

    /**
     * 解析Audio路径。
     *
     * @return 路径 对象
     */
    private Path resolveAudioPath() {
        if (setting.getAudioPath() != null) {
            return setting.getAudioPath();
        }
        if (setting.getAudio() == null && setting.getAudioInput() == null) {
            throw new IllegalStateException("No audio input configured");
        }
        try {
            Path tmp = Files.createTempFile("zipformer-audio-", ".wav");
            if (setting.getAudio() != null) {
                Files.write(tmp, setting.getAudio());
            } else {
                try (InputStream in = setting.getAudioInput()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (Exception e) {
            throw new RuntimeException("Failed to materialize audio input", e);
        }
    }

    /**
     * 缓存根节点。
     *
     * @return 结果字符串
     */
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

