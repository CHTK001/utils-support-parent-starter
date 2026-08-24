package com.chua.deeplearning.support.onnx.audio.sensevoice;

import com.chua.common.support.ai.audio.AudioClient;
import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 基于 ONNX Runtime 的本地 SenseVoice ASR 客户端。
 *
 * <p>SenseVoice-small 支持中/英/日/韩/粤五种语言，含 ITN 数字归一化，
 * 中文效果优于同级 Whisper。通过 {@link SenseVoiceTranslator} 在本地 CPU
 * 端完成 fbank → LFR → CMVN → CTC 四阶段离线推理。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   String text = AudioClient.create("sensevoice", "")
 *       .transcribe(Path.of("audio.wav"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"sensevoice", "sensevoice-small", "sense-voice"})
public class SenseVoiceAudioClient implements AudioClient {

    /**
     * 默认模型名
     */
    private static final String DEFAULT_MODEL = "sensevoice-small";

    /**
     * classpath 资源根路径
     */
    private static final String RESOURCE_BASE = "audio/asr/";

    /**
     * JAR 内资源目录名
     */
    private static final String RESOURCE_DIR = "sensevoice-small";

    /**
     * 缓存根相对路径
     */
    private static final String CACHE_ROOT = "audio/asr/";

    /**
     * 临时音频文件前缀
     */
    private static final String TMP_AUDIO_PREFIX = "sensevoice-audio-";

    /**
     * 临时音频文件后缀
     */
    private static final String TMP_AUDIO_SUFFIX = ".wav";

    /**
     * 任务 ID 前缀
     */
    private static final String TASK_ID_PREFIX = "sensevoice-";

    /** 配置 */
    private final AudioClientSetting setting;
    /** 模型名 */
    private String model;
    /** 语言（zh/en/ja/ko/yue/auto） */
    private String language;
    /** 音频字节 */
    private byte[] audio;
    /** 音频路径 */
    private Path audioPath;
    /** 音频流 */
    private InputStream audioInput;
    /** 推理器 */
    private SenseVoiceTranslator translator;
    /** 是否就绪 */
    private boolean prepared;

    /**
     * 构造客户端。
     *
     * @param setting 配置
     */
    public SenseVoiceAudioClient(AudioClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.language = setting.getLanguage();
        this.audio = setting.getAudio();
        this.audioPath = setting.getAudioPath();
        this.audioInput = setting.getAudioInput();
        this.translator = new SenseVoiceTranslator();
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
            String text = translator.transcribe(target, language);
            log.info("[SenseVoice] transcribe {}ms: {}", System.currentTimeMillis() - t0, text);
            return text;
        } catch (Exception e) {
            log.error("[SenseVoice] transcribe failed: {}", e.getMessage(), e);
            throw new RuntimeException("SenseVoice transcribe failed", e);
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
            String transcript = translator.transcribe(target, language);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(transcript)
                    .detectedLanguage(language != null ? language : "auto")
                    .build();
        } catch (Exception e) {
            log.error("[SenseVoice] queryTask failed: {}", e.getMessage(), e);
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
            boolean ready = Files.exists(modelDir.resolve("model.int8.onnx"))
                    && Files.exists(modelDir.resolve("tokens.txt"));
            if (!ready) {
                com.chua.common.support.utils.NativeLoader
                        .of("sensevoice-resources")
                        .from(SenseVoiceAudioClient.class.getClassLoader())
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
            throw new RuntimeException("SenseVoice model prepare failed", e);
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
            throw new IllegalStateException("No audio input configured");
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
