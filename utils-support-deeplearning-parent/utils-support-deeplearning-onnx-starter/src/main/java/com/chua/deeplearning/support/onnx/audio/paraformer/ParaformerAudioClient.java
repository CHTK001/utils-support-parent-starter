package com.chua.deeplearning.support.onnx.audio.paraformer;

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
* 基于 ONNX Runtime 的本地 Paraformer 中文 ASR 客户端。
* <p>
* 通过 {@link ParaformerTranslator} 在本地 CPU 端进行语音转写（Paraformer 非自回归，
* 单次前向即可输出全帧结果），不依赖云服务，适合离线 / 隐私 / 嵌入式场景。
* </p>
* <p>
* 用法：
* <pre>{@code
*   String text = VirtualClient.create("paraformer", "")
*       .model("paraformer-zh-small")
*       .transcribe(Path.of("audio.wav"));
* }</pre>(Path.of("audio.wav"));
* }</pre>
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi({"paraformer", "paraformer-zh-small", "paraformer-onnx", "sherpa-onnx-paraformer"})
public class ParaformerAudioClient implements VirtualClient {

    /** 默认模型名 */
    /** 默认_模型 */
    private static final String DEFAULT_MODEL = "paraformer-zh-small";

    /** 类路径 资源根路径 */
    /** Resource_基础 */
    private static final String RESOURCE_BASE = "audio/asr/";

    /** 模型缓存根目录（相对 deeplearning.模型.缓存-dir 或 %TEMP%） */
    /** 缓存_根 */
    private static final String CACHE_ROOT = "audio/asr/";

    /** 临时音频文件名前缀 */
    /** Tmp_音频_前缀 */
    private static final String TMP_AUDIO_PREFIX = "paraformer-audio-";

    /** 临时音频文件名后缀 */
    /** Tmp_音频_后缀 */
    private static final String TMP_AUDIO_SUFFIX = ".wav";

    /** 任务 标识 前缀 */
    /** 任务_标识_前缀 */
    private static final String TASK_ID_PREFIX = "paraformer-";

    /** 设置 */
    private final AudioClientSetting setting;

    /** 模型 */
    private String model;

    /** 语言 */
    private String language;

    /** 覆盖采样率 */
    /** Override 采样率 */
    private Integer overrideSampleRate;

    /** 格式 */
    private String format;

    /** 提示词 */
    /** 提示符 */
    private String prompt;

    /** 温度 */
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
    private ParaformerTranslator translator;

    /** 是否已准备 */
    /** Prepared */
    private boolean prepared;

    /**
    * 创建 paraformer音频客户端 实例
    * @param setting setting
     */
    public ParaformerAudioClient(AudioClientSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.language = setting.getLanguage();
        this.prompt = setting.getPrompt();
        this.audio = setting.getAudio();
        this.audioPath = setting.getAudioPath();
        this.audioInput = setting.getAudioInput();
        this.translator = new ParaformerTranslator();
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
            log.error("[ParaformerAudioClient] transcribe failed: {}", e.getMessage(), e);
            throw new RuntimeException("Paraformer transcribe failed", e);
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
            log.error("[ParaformerAudioClient] queryTask failed: {}", e.getMessage(), e);
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
    * 确保模型资源已解压并加载。
     */
    private void ensurePrepared() {
        try {
            String modelName = model != null ? model : DEFAULT_MODEL;
            Path modelDir = Path.of(cacheRoot(), CACHE_ROOT + modelName);
            if (!Files.isRegularFile(modelDir.resolve("model.int8.onnx"))) {
                NativeLoader.of("paraformer-resources")
                        .from(ParaformerAudioClient.class.getClassLoader())
                        .basePath(RESOURCE_BASE + modelName + "/")
                        .toTarget(modelDir)
                        .glob("*")
                        .withMd5(true)
                        .extractOnly(true)
                        .load();
            }
            translator.prepare(modelDir);
            prepared = true;
        } catch (Exception e) {
            throw new RuntimeException("Paraformer model prepare failed", e);
        }
    }

    /**
    * 模型缓存根目录：优先读系统属性 deeplearning.模型.缓存-dir，
    * 未配置时回落 %TEMP%。
    *
    * @return 缓存根目录
     */
    private static String cacheRoot() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        return (prop != null && !prop.isBlank()) ? prop.trim() : System.getProperty("java.io.tmpdir");
    }

    /**
    * 解析待转写音频路径。
    *
    * @return 音频文件路径
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
