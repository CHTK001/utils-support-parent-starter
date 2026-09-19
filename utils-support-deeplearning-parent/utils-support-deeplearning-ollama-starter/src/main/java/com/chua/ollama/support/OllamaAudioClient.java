package com.chua.ollama.support;

import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.annotations.Spi;
import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.generate.OllamaGenerateRequest;
import io.github.ollama4j.models.response.Model;
import io.github.ollama4j.models.response.OllamaResult;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Ollama 本地语音识别（ASR）客户端（SPI provider="ollama"）。
 *
 * <p>基于 ollama4j 原生 API（{@code POST /api/generate}）调用 whisper 类
 * 语音 识别 模型（如 {@code whisper}、{@code llama3.2-vision} 等 支持 音频 输入 的 模型），
 * 将 音频 文件 按 提示词 方式 转写为 文字。Ollama 的 ASR 能力 依赖 服务 端 已 加载
 * 对应 的 语音 模型，未 加载 时 底层 返回 模型 未 找到 错误。
 * 模型列表 通过 {@code listModels()} 动态 获取。
 * 默认 地址 {@code http://localhost:11434}，无需 API Key。
 *
 * <p>调用 示例：
 * <pre>{@code
 *   String text = VirtualClient.create("ollama", "")
 *       .model("whisper")
 *       .language("zh")
 *       .transcribe(Path.of("audio.wav"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("ollama")
public class OllamaAudioClient implements VirtualClient {

    /**
     * 客户端 配置
     */
    private final AudioClientSetting setting;

    /**
     * ollama4j 原生 客户端
     */
    private final Ollama ollama;

    /**
     * 当前 模型 名称
     */
    private String model;

    /**
     * 音频 语言
     */
    private String language;

    /**
     * 异步 任务 缓存（Ollama 无 原生 异步，以 本地 记录 模拟 轮询 契约）。
     * <p>使用 有界 缓存 防止 长时间 运行 下 内存 无界 增长；超出 容量 时 最老 任务 被 驱逐。</p>
     */
    private final Map<String, AudioResponse> taskCache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, AudioResponse>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<String, AudioResponse> eldest) {
                            return size() > 256;
                        }
                    });

    /**
     * 创建 Ollama 语音 识别 客户端。
     *
     * @param setting 客户端 配置（provider 应为 "ollama"，apiKey 可为 空）
     */
    public OllamaAudioClient(AudioClientSetting setting) {
        this.setting = setting;
        this.ollama = OllamaSupport.client(setting != null ? setting.getBaseUrl() : null);
        this.model = setting != null ? setting.getModel() : null;
        this.language = setting != null ? setting.getLanguage() : null;
    }

    @Override
    /** 提供者 */
    public VirtualClient provider(String provider) {
        return this;
    }

    @Override
    /** 模型 */
    public VirtualClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /** 语言 */
    public VirtualClient language(String language) {
        this.language = language;
        return this;
    }

    @Override
    /** 语音转写 */
    public String transcribe(Path path) {
        byte[] audioBytes = readAudio(path, setting != null ? setting.getAudio() : null);
        String prompt = buildTranscribePrompt(audioBytes);
        OllamaGenerateRequest request = new OllamaGenerateRequest(
                model != null && !model.isBlank() ? model : "whisper", prompt);
        try {
            OllamaResult result = ollama.generate(request, null);
            return result != null ? result.getResponse() : null;
        } catch (io.github.ollama4j.exceptions.OllamaException e) {
            throw OllamaSupport.wrap("transcribe", e);
        }
    }

    @Override
    /** 创建任务 */
    public String createTask(Path path) {
        String taskId = "ollama-asr-" + System.currentTimeMillis();
        AudioResponse response;
        try {
            String text = transcribe(path);
            response = AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.SUCCESS)
                    .transcript(text)
                    .detectedLanguage(language)
                    .progress(100)
                    .build();
        } catch (Exception e) {
            response = AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
        }
        taskCache.put(taskId, response);
        return taskId;
    }

    @Override
    /** 查询任务 */
    public AudioResponse queryTask(String taskId) {
        AudioResponse response = taskCache.get(taskId);
        if (response == null) {
            return AudioResponse.builder()
                    .taskId(taskId)
                    .status(AudioResponse.Status.FAILED)
                    .errorMessage("未知 任务: " + taskId)
                    .build();
        }
        return response;
    }

    @Override
    /** 模型列表 */
    public List<ModelDefinition> models() {
        try {
            List<Model> raw = ollama.listModels();
            List<ModelDefinition> result = new ArrayList<>();
            if (raw != null) {
                for (Model m : raw) {
                    if (m == null || m.getName() == null || m.getName().isBlank()) {
                        continue;
                    }
                    result.add(ModelDefinition.builder()
                            .id(m.getName())
                            .name(m.getName())
                            .provider("ollama")
                            .description("本地 Ollama 模型")
                            .capabilities(List.of("audio-transcribe"))
                            .build());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[Ollama] 获取 模型列表 异常: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        taskCache.clear();
    }

    /**
     * 读取 音频 字节：优先 使用 已 配置 的 字节 数组，其次 读取 文件 路径。
     *
     * @param path  音频 文件 路径（可为 空）
     * @param audio 已 配置 的 音频 字节（可为 空）
     * @return 音频 字节 数组
     */
    private byte[] readAudio(Path path, byte[] audio) {
        if (audio != null && audio.length > 0) {
            return audio;
        }
        if (path == null) {
            throw new IllegalArgumentException("未 提供 音频 输入（path 与 audio 均 为 空）");
        }
        try {
            return Files.readAllBytes(path);
        } catch (Exception e) {
            throw OllamaSupport.wrap("读取 音频 文件", e);
        }
    }

    /**
     * 构建 转写 提示词：将 音频 以 Base64 形式 嵌入 提示词，交由 语音 模型 解析。
     *
     * <p>Ollama 的 音频 输入 依赖 模型 侧 对 内嵌 数据 的 支持；此 处 以
     * 文本 化 方式 传递，兼容 支持 该 方式 的 语音 模型。</p>
     *
     * @param audioBytes 音频 字节
     * @return 提示词
     */
    private String buildTranscribePrompt(byte[] audioBytes) {
        String encoded = Base64.getEncoder().encodeToString(audioBytes);
        StringBuilder sb = new StringBuilder();
        if (language != null && !language.isBlank()) {
            sb.append("识别 语言 为 ").append(language).append(" 的 音频：");
        } else {
            sb.append("识别 以下 音频 的 文字 内容：");
        }
        sb.append("\n<audio>").append(encoded).append("</audio>");
        return sb.toString();
    }
}
