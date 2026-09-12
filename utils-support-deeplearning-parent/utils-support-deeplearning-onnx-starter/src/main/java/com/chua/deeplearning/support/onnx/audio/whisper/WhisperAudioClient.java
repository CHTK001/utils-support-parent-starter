package com.chua.deeplearning.support.onnx.audio.whisper;

import com.chua.common.support.ai.audio.VirtualClient;
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
 * 基于 ONNX Runtime 的本地 Whisper ASR 客户端。
 *
 * <p>通过 {@link WhisperTranslator} 在本地 CPU 端进行 30 秒窗口的语音转写，
 * 不依赖任何云服务，适合离线 / 隐私 / 嵌入式场景。
 *
 * <p>用法：
 * <pre>{@code
 *   String text = VirtualClient.create("whisper", "")
 *       .model("whisper-tiny")
 *       .language("zh")
 *       .transcribe(Path.of("audio.wav"));
 * }</pre>(Path.of("audio.wav"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"whisper", "whisper-tiny", "whisper-onnx"})
public class WhisperAudioClient implements VirtualClient {

    /**
     * 默认模型名
     */
    private static final String DEFAULT_MODEL = "whisper-tiny";

    /**
      * 类路径 资源根路径
     */
    private static final String RESOURCE_BASE = "audio/asr/";

    /**
     * 模型缓存根目录（相对 {@code deeplearning.model.cache-dir} 或 {@code %TEMP%}）
     */
    private static final String CACHE_ROOT = "audio/asr/";

    /**
     * 临时音频文件名前缀
     */
    private static final String TMP_AUDIO_PREFIX = "whisper-audio-";

    /**
     * 临时音频文件名后缀
     */
    private static final String TMP_AUDIO_SUFFIX = ".wav";

    /**
      * 任务 标识 前缀
     */
    private static final String TASK_ID_PREFIX = "whisper-";

    /** 设置 */
    private final AudioClientSetting setting;
    /** 模型 */
    private String model;
    /** 语言 */
    private String language;
    /** 覆盖采样率 */
    /** Override示例比率 */
    private Integer overrideSampleRate;
    /** 模型三维格式 */
    /** 格式 */
    private String format;
    /** 提示词 */
    /** 提示符 */
    private String prompt;
    /** 温度参数 */
    /** Temperature */
    private Double temperature;
    /** 随机种子 */
    /** Seed */
    private Long seed;
    /** 音频数据 */
    /** 音频 */
    private byte[] audio;
    /** 音频文件路径 */
    /** 音频路径 */
    private Path audioPath;
    /** 音频输入流 */
    /** 音频输入 */
    private InputStream audioInput;

    /** 翻译器 */
    /** Translator */
    private WhisperTranslator translator;
    /** 是否已准备 */
    /** Prepared */
    private boolean prepared;

    /**
      * 创建 whisper音频客户端 实例
     * @param setting setting
     */
    public WhisperAudioClient(AudioClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.language = setting.getLanguage();
        this.prompt = setting.getPrompt();
        this.audio = setting.getAudio();
        this.audioPath = setting.getAudioPath();
        this.audioInput = setting.getAudioInput();
        this.translator = new WhisperTranslator();
    }

    @Override
    /** 模型 */
    public VirtualClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** Language */
    public VirtualClient language(String language) {
        this.language = language;
        return this;
    }

    @Override
    /** 样本rate */
    public VirtualClient sampleRate(Integer sampleRate) {
        this.overrideSampleRate = sampleRate;
        return this;
    }

    @Override
    /** 格式化 */
    public VirtualClient format(String format) {
        this.format = format;
        return this;
    }

    @Override
    /** 提示符 */
    public VirtualClient prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    @Override
    /** Temperature */
    public VirtualClient temperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    @Override
    /** Seed */
    public VirtualClient seed(Long seed) {
        this.seed = seed;
        return this;
    }

    @Override
    /** 音频 */
    public VirtualClient audio(byte[] audio) {
        this.audio = audio;
        this.audioPath = null;
        this.audioInput = null;
        return this;
    }

    @Override
    /** 音频 */
    public VirtualClient audio(InputStream input) {
        this.audioInput = input;
        this.audioPath = null;
        this.audio = null;
        return this;
    }

    @Override
    /** 音频 */
    public VirtualClient audio(Path path) {
        this.audioPath = path;
        this.audio = null;
        this.audioInput = null;
        return this;
    }

    @Override
    /** Transcribe */
    public String transcribe(Path path) {
        if (path != null) {
            this.audioPath = path;
        }
        if (!prepared) {
            ensurePrepared();
        }
        try {
            Path target = resolveAudioPath();
            return translator.transcribe(target);
        } catch (Exception e) {
            log.error("[WhisperAudioClient] transcribe failed: {}", e.getMessage(), e);
            throw new RuntimeException("Whisper transcribe failed", e);
        }
    }

    @Override
    /** 创建任务 */
    public String createTask(Path path) {
        if (path != null) {
            this.audioPath = path;
        }
        return TASK_ID_PREFIX + UUID.randomUUID();
    }

    @Override
    /** 查询任务 */
    public AudioResponse queryTask(String taskId) {
        if (!prepared) {
            ensurePrepared();
        }
        try {
            String transcript = transcribe(audioPath);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(transcript)
                    .detectedLanguage(language)
                    .build();
        } catch (Exception e) {
            log.error("[WhisperAudioClient] queryTask failed: {}", e.getMessage(), e);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /** ensureprepared */
    private void ensurePrepared() {
        try {
            String modelName = model != null ? model : DEFAULT_MODEL;
            Path modelDir = Path.of(cacheRoot(), CACHE_ROOT + modelName);
            if (!Files.isDirectory(modelDir.resolve("onnx"))) {
                NativeLoader.of("whisper-resources")
                        .from(WhisperAudioClient.class.getClassLoader())
                        .basePath(RESOURCE_BASE + modelName + "/")
                        .toTarget(modelDir)
                        .glob("*")
                        .withMd5(true)
                        .extractOnly(true)
                        .load();
            }
            translator.prepare(modelDir);
            if (language != null && !language.isBlank()) {
                translator.setLanguage(language);
            }
            prepared = true;
        } catch (Exception e) {
            throw new RuntimeException("Whisper model prepare failed", e);
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

    /**
     * 解析音频路径
     *
     * @return resolve音频路径的结果
     */
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
    /** 关闭 */
    public void close() {
        translator = null;
        prepared = false;
    }
}
