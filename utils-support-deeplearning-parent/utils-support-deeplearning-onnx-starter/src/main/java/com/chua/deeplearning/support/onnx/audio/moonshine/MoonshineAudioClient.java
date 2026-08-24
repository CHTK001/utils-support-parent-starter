package com.chua.deeplearning.support.onnx.audio.moonshine;

import com.chua.common.support.ai.audio.AudioClient;
import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.common.support.utils.NativeLoader;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 基于 ONNX Runtime 的本地 Moonshine ASR 客户端。
 *
 * <p>Moonshine 为轻量级英文语音识别模型，速度显著快于同精度 Whisper，
 * 适合边缘设备实时转写。通过 {@link MoonshineTranslator} 在本地 CPU 端
 * 完成 preprocess → encode → uncached/cached decode 四阶段推理。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   String text = AudioClient.create("moonshine", "")
 *       .transcribe(Path.of("audio.wav"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"moonshine", "moonshine-base", "moonshine-tiny", "moonshine-onnx"})
public class MoonshineAudioClient implements AudioClient {

    /**
     * 默认模型名
     */
    private static final String DEFAULT_MODEL = "moonshine-base";

    /**
     * classpath 资源根路径
     */
    private static final String RESOURCE_BASE = "nlp/audio/";

    /**
     * JAR 内资源目录名（模型权重所在）
     */
    private static final String RESOURCE_DIR = "moonshine";

    /**
     * 缓存根相对路径
     */
    private static final String CACHE_ROOT = "nlp/audio/";

    /**
     * 临时音频文件前缀
     */
    private static final String TMP_AUDIO_PREFIX = "moonshine-audio-";

    /**
     * 临时音频文件后缀
     */
    private static final String TMP_AUDIO_SUFFIX = ".wav";

    /**
     * 任务 ID 前缀
     */
    private static final String TASK_ID_PREFIX = "moonshine-";

    /** 配置 */
    private final AudioClientSetting setting;
    /** 模型名 */
    private String model;
    /** 语言（保留接口兼容，moonshine 仅英文） */
    private String language;
    /** 音频字节 */
    private byte[] audio;
    /** 音频路径 */
    private Path audioPath;
    /** 音频流 */
    private InputStream audioInput;
    /** 推理器 */
    private MoonshineTranslator translator;
    /** 是否就绪 */
    private boolean prepared;

    /**
     * 构造客户端。
     *
     * @param setting 配置
     */
    public MoonshineAudioClient(AudioClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.language = setting.getLanguage();
        this.audio = setting.getAudio();
        this.audioPath = setting.getAudioPath();
        this.audioInput = setting.getAudioInput();
        this.translator = new MoonshineTranslator();
    }

    @Override
    public AudioClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public AudioClient language(String language) {
        this.language = language;
        return this;
    }

    @Override
    public AudioClient sampleRate(Integer sampleRate) {
        // moonshine 固定 16kHz，忽略覆盖值
        return this;
    }

    @Override
    public AudioClient format(String format) {
        return this;
    }

    @Override
    public AudioClient prompt(String prompt) {
        return this;
    }

    @Override
    public AudioClient temperature(Double temperature) {
        return this;
    }

    @Override
    public AudioClient seed(Long seed) {
        return this;
    }

    @Override
    public AudioClient audio(byte[] audio) {
        this.audio = audio;
        this.audioPath = null;
        this.audioInput = null;
        return this;
    }

    @Override
    public AudioClient audio(InputStream input) {
        this.audioInput = input;
        this.audioPath = null;
        this.audio = null;
        return this;
    }

    @Override
    public AudioClient audio(Path path) {
        this.audioPath = path;
        this.audio = null;
        this.audioInput = null;
        return this;
    }

    @Override
    public String transcribe(Path path) {
        if (path != null) {
            this.audioPath = path;
        }
        ensurePrepared();
        try {
            Path target = resolveAudioPath();
            long t0 = System.currentTimeMillis();
            String text = translator.transcribe(target);
            log.info("[Moonshine] transcribe {}ms: {}", System.currentTimeMillis() - t0, text);
            return text;
        } catch (Exception e) {
            log.error("[Moonshine] transcribe failed: {}", e.getMessage(), e);
            throw new RuntimeException("Moonshine transcribe failed", e);
        }
    }

    @Override
    public String createTask(Path path) {
        if (path != null) {
            this.audioPath = path;
        }
        return TASK_ID_PREFIX + UUID.randomUUID();
    }

    @Override
    public AudioResponse queryTask(String taskId) {
        try {
            Path target = resolveAudioPath();
            ensurePrepared();
            String transcript = translator.transcribe(target);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(transcript)
                    .detectedLanguage(language != null ? language : "en")
                    .build();
        } catch (Exception e) {
            log.error("[Moonshine] queryTask failed: {}", e.getMessage(), e);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /** 确保模型已从 classpath 解压并加载 */
    private void ensurePrepared() {
        if (prepared) {
            return;
        }
        try {
            Path modelDir = Path.of(cacheRoot(), CACHE_ROOT, RESOURCE_DIR);
            boolean ready = Files.exists(modelDir.resolve("vocab.json"))
                    && Files.exists(modelDir.resolve("encode.int8.onnx"));
            if (!ready) {
                NativeLoader.of("moonshine-resources")
                        .from(MoonshineAudioClient.class.getClassLoader())
                        .basePath(RESOURCE_BASE + RESOURCE_DIR + "/")
                        .toTarget(modelDir)
                        .glob("*")
                        .withMd5(true)
                        .extractOnly(true)
                        .load();
            }
            translator.prepare(modelDir);
            prepared = true;
        } catch (Exception e) {
            throw new RuntimeException("Moonshine model prepare failed", e);
        }
    }

    /**
     * 模型缓存根目录：优先读系统属性 {@code deeplearning.model.cache-dir}，
     * 未配置时回落 {@code %TEMP%}。
     *
     * @return 缓存根目录
     */
    private static String cacheRoot() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        return (prop != null && !prop.isBlank()) ? prop.trim() : System.getProperty("java.io.tmpdir");
    }

    /** 将 bytes/stream 输入物化为临时文件 */
    private Path resolveAudioPath() {
        if (audioPath != null) {
            return audioPath;
        }
        if (audio == null && audioInput == null) {
            throw new IllegalStateException("No audio input configured (call audio(path|bytes|stream) first)");
        }
        try {
            Path tmp = Files.createTempFile(TMP_AUDIO_PREFIX, TMP_AUDIO_SUFFIX);
            if (audio != null) {
                Files.write(tmp, audio);
            } else {
                try (InputStream in = audioInput) {
                    Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (Exception e) {
            throw new RuntimeException("Failed to materialize audio input", e);
        }
    }

    @Override
    public void close() {
        if (translator != null) {
            translator.close();
        }
        prepared = false;
    }
}
