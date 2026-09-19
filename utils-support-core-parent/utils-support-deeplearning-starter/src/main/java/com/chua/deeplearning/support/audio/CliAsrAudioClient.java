package com.chua.deeplearning.support.audio;

import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.engine.CliModelRunner;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于外部 CLI 运行时（nemo-speech）的本地 ASR 客户端，
 * 覆盖 Parakeet TDT 0.6B v3 等 NeMo Speech 系多语言 ASR 模型。
 *
 * <p>Provider 名称：{@code parakeet} / {@code parakeet-v3} / {@code nemo-speech} /
 * {@code nemo-speech-asr}。</p>
 *
 * <p>运行时由 {@link CliModelRunner} 定位（PATH / 显式路径 / downloadUrl 自动安装）；
 * 模型 GGUF 由 {@code ModelRegistry} 托管下载（注册见 {@link CliAsrModelRegistrar}），
 * 以本地路径交给 CLI。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   String text = VirtualClient.create("parakeet-v3", "")
 *       .transcribe(Path.of("audio.wav"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"parakeet", "parakeet-v3", "nemo-speech", "nemo-speech-asr"})
public class CliAsrAudioClient implements VirtualClient {

    /** 默认 模型 标识（ModelRegistry 注册项，见 CliAsrModelRegistrar） */
    private static final String DEFAULT_MODEL = "parakeet-v3";

    /** 异步 任务 前缀 */
    private static final String TASK_PREFIX = "cli-asr-";

    /** 配置 */
    private final AudioClientSetting setting;
    /** 当前 模型 */
    private String model;
    /** 语言 */
    private String language;
    /** 任务 缓存（CLI 无原生 异步； 本地 记录 模拟 轮询 契约） */
    private final Map<String, AudioResponse> taskCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, AudioResponse>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AudioResponse> eldest) {
                    return size() > 256;
                }
            });

    /**
     * 创建 CLI ASR 客户端
     *
     * @param setting 配置
     */
    public CliAsrAudioClient(AudioClientSetting setting) {
        this.setting = setting;
        this.model = setting != null ? setting.getModel() : null;
        this.language = setting != null ? setting.getLanguage() : null;
    }

    @Override
    public VirtualClient model(String m) {
        this.model = m;
        return this;
    }

    @Override
    public VirtualClient language(String l) {
        this.language = l;
        return this;
    }

    @Override
    public VirtualClient sampleRate(Integer sr) {
        return this;
    }

    @Override
    public VirtualClient format(String fmt) {
        return this;
    }

    @Override
    public VirtualClient prompt(String p) {
        return this;
    }

    @Override
    public VirtualClient temperature(Double t) {
        return this;
    }

    @Override
    public VirtualClient seed(Long s) {
        return this;
    }

    @Override
    public VirtualClient audio(byte[] a) {
        if (setting != null) {
            setting.setAudio(a);
        }
        return this;
    }

    @Override
    public VirtualClient audio(java.io.InputStream in) {
        if (setting != null) {
            setting.setAudioInput(in);
        }
        return this;
    }

    @Override
    public VirtualClient audio(Path p) {
        if (setting != null) {
            setting.setAudioPath(p);
        }
        return this;
    }

    @Override
    public String transcribe(Path path) {
        try {
            Path target = path != null ? path : resolveAudioPath();
            byte[] data = Files.readAllBytes(target);
            return transcribeBytes(data);
        } catch (Exception e) {
            log.error("[CliAsrAudioClient] 转写 失败: {}", e.getMessage(), e);
            throw new RuntimeException("CLI ASR 转写 失败", e);
        }
    }

    /**
     * 字节 转写
     *
     * @param audio 音频
     * @return 文本
     * @throws Exception 失败
     */
    public String transcribeBytes(byte[] audio) throws Exception {
        CliAsrTranslator t = translator();
        return t.transcribe(audio);
    }

    /**
     * 构建/复用 CLI 翻译器
     *
     * @return 翻译器
     */
    private CliAsrTranslator translator() {
        String m = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        CliAsrTranslator t = new CliAsrTranslator(CliModelRunner.nemoSpeech(), m);
        return t;
    }

    @Override
    public String createTask(Path path) {
        return TASK_PREFIX + UUID.randomUUID();
    }

    @Override
    public AudioResponse queryTask(String taskId) {
        AudioResponse cached = taskCache.get(taskId);
        if (cached != null) {
            return cached;
        }
        try {
            Path target = resolveAudioPath();
            byte[] data = Files.readAllBytes(target);
            String text = translator().transcribe(data);
            AudioResponse resp = AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(text)
                    .detectedLanguage(language)
                    .build();
            taskCache.put(taskId, resp);
            return resp;
        } catch (Exception e) {
            log.error("[CliAsrAudioClient] 查询 任务 失败: {}", e.getMessage(), e);
            AudioResponse resp = AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
            taskCache.put(taskId, resp);
            return resp;
        }
    }

    @Override
    public List<com.chua.common.support.ai.chat.ModelDefinition> models() {
        return List.of(
                com.chua.common.support.ai.chat.ModelDefinition.builder()
                        .id(DEFAULT_MODEL)
                        .name("Parakeet-TDT-0.6B-v3 (nemo-speech)")
                        .description("NVIDIA Parakeet TDT 0.6B v3 多语言 ASR（25 欧洲语言，自标点；GGUF 由 ModelRegistry 下载托管）")
                        .build()
        );
    }

    /**
     * 解析 音频 路径
     *
     * @return 路径
     */
    private Path resolveAudioPath() {
        if (setting != null) {
            if (setting.getAudioPath() != null) {
                return setting.getAudioPath();
            }
            if (setting.getAudio() != null) {
                try {
                    Path tmp = Files.createTempFile(TASK_PREFIX, ".wav");
                    Files.write(tmp, setting.getAudio());
                    tmp.toFile().deleteOnExit();
                    return tmp;
                } catch (Exception e) {
                    throw new RuntimeException("音频 字节 落盘 失败", e);
                }
            }
            if (setting.getAudioInput() != null) {
                try {
                    Path tmp = Files.createTempFile(TASK_PREFIX, ".wav");
                    Files.copy(setting.getAudioInput(), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    tmp.toFile().deleteOnExit();
                    return tmp;
                } catch (Exception e) {
                    throw new RuntimeException("音频 流 落盘 失败", e);
                }
            }
        }
        throw new IllegalStateException("未配置 音频 输入（path / bytes / stream 均未提供）");
    }

    @Override
    public void close() {
        taskCache.clear();
    }
}
