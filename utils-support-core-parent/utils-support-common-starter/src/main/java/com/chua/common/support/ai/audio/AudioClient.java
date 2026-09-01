package com.chua.common.support.ai.audio;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.pool.PooledObjectClient;
import com.chua.common.support.spi.ServiceProvider;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * AI 语音识别（ASR）客户端接口
 *
 * <p>提供统一的语音转文字服务抽象，支持同步转写和异步任务两种模式。
 * 实现类通过 SPI 机制按 provider 名称注册，调用方通过工厂方法获取实例。
 *
 * <p>同步转写示例：
 * <pre>{@code
 *   String text = AudioClient.create("whisper", "sk-xxx")
 *       .model("whisper-1")
 *       .language("zh")
 *       .transcribe(Path.of("audio.wav"));
 * }</pre>
 *
 * <p>异步任务示例：
 * <pre>{@code
 *   String taskId = AudioClient.create("alibaba-asr", "sk-xxx")
 *       .model("paraformer-v2")
 *       .createTask(Path.of("audio.wav"));
 *
 *   // 轮询查询任务结果
 *   AudioResponse resp = client.queryTask(taskId);
 *   if (resp.getStatus() == AudioResponse.Status.SUCCESS) {
 *       String transcript = resp.getTranscript();
 *   }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface AudioClient extends AutoCloseable, PooledObjectClient<AudioClient> {

    /**
     * 创建指定 provider 的语音识别客户端
     *
     * @param provider AI 服务商名称，如 "openai"、"whisper"、"alibaba-asr" 等
     * @param apiKey   API 密钥
     * @return AudioClient 实例
     */
    static AudioClient create(String provider, String apiKey) {
        return ServiceProvider.of(AudioClient.class)
                .getNewExtension(provider, AudioClientSetting.builder()
                        .provider(provider).appKey(apiKey).build());
    }

    /**
     * 通过完整配置创建语音识别客户端
     *
     * @param setting 客户端配置，包含 provider、apiKey、baseUrl、model 等
     * @return AudioClient 实例
     */
    static AudioClient create(AudioClientSetting setting) {
        return ServiceProvider.of(AudioClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
     * 创建指定 provider 和自定义地址的语音识别客户端
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @param baseUrl  自定义 API 基地址
     * @return AudioClient 实例
     */
    static AudioClient create(String provider, String apiKey, String baseUrl) {
        return ServiceProvider.of(AudioClient.class)
                .getNewExtension(provider, AudioClientSetting.builder()
                        .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    /**
     * 设置 AI 服务商
     *
     * @param provider 服务商名称
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称
     *
     * @param model 模型名称，如 "whisper-1"、"whisper-tiny"、"paraformer-v2" 等
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient model(String model) {
        return this;
    }

    /**
     * 设置音频语言
     *
     * <p>显式指定音频语言可提升识别准确率与速度。
     * 留空则由模型自动检测（如 Whisper 自动识别语种）。
     * 常见值："zh"（中文）、"en"（英文）、"ja"（日文）等 ISO 639-1 代码。
     *
     * @param language 语言代码
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient language(String language) {
        return this;
    }

    /**
     * 设置采样率
     *
     * <p>大多数 ASR 服务会自动重采样，可选覆盖。
     * 常见值：16000、24000、44100、48000。
     *
     * @param sampleRate 采样率（Hz）
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient sampleRate(Integer sampleRate) {
        return this;
    }

    /**
     * 设置音频格式
     *
     * <p>如 "wav"、"mp3"、"m4a"、"flac" 等。
     * 留空则由实现类从文件扩展名推断。
     *
     * @param format 音频格式
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient format(String format) {
        return this;
    }

    /**
     * 设置提示词
     *
     * <p>用于引导模型识别特定术语、专有名词或语种风格。
     * 例如医学会议、客服录音中的专有词汇。
     *
     * @param prompt 提示词
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient prompt(String prompt) {
        return this;
    }

    /**
     * 设置温度
     *
     * <p>采样温度，0 表示最确定（贪心），越高结果越发散。
     * 大多数 ASR 服务默认 0，温度仅在少数支持采样的 ASR 模型中生效。
     *
     * @param temperature 温度
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient temperature(Double temperature) {
        return this;
    }

    /**
     * 设置随机种子
     *
     * @param seed 随机种子
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient seed(Long seed) {
        return this;
    }

    /**
     * 设置音频字节数据
     *
     * <p>部分服务支持直接上传音频二进制，
     * 优先于文件路径方式。
     *
     * @param audio 音频字节
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient audio(byte[] audio) {
        return this;
    }

    /**
     * 设置音频输入流
     *
     * @param input 音频输入流
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient audio(InputStream input) {
        return this;
    }

    /**
     * 设置音频文件路径
     *
     * @param path 音频文件路径
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient audio(Path path) {
        return this;
    }

    /**
     * 设置说话人数
     *
     * <p>部分服务支持说话人分离（diarization），
     * 0 表示由模型自动推断。
     *
     * @param speakers 说话人数
     * @return 当前客户端实例，支持链式调用
     */
    default AudioClient speakers(Integer speakers) {
        return this;
    }

    /**
     * 同步转写音频为文字
     *
     * <p>阻塞等待服务端返回完整识别结果。
     *
     * @param path 音频文件路径
     * @return 转写得到的文字
     */
    String transcribe(Path path);

    /**
     * 同步转写音频为文字（使用已配置的 audio 字节）
     *
     * @return 转写得到的文字
     */
    default String transcribe() {
        return transcribe((Path) null);
    }

    // ==================== 流式转录门面 ====================

    /**
     * 流式转写：一次性传入完整音频样本，返回增量识别结果。
     *
     * <p>适用于麦克风实时输入场景，内部按 chunk 送入模型并累积输出。
     * 不支持流式的客户端直接委托给 {@link #transcribe()}。
     *
     * <pre>{@code
     *   AudioClient client = AudioClient.create("zipformer-zh", "");
     *   // 从麦克风逐片读入 16kHz float samples（每片约 2560 samples = 160ms）
     *   StringBuilder sb = new StringBuilder();
     *   while (hasMore) {
     *       float[] chunk = readMicrophoneChunk();
     *       client.feedAudio(chunk);
     *       String incremental = client.getResult();
     *       if (incremental != null && !incremental.isEmpty()) {
     *           sb.append(incremental);
     *           System.out.println("[stream] " + sb);
     *       }
     *   }
     *   String finalText = client.complete();
     * }</pre>
     *
     * @param samples 音频样本数组（16kHz mono，float 范围 [-1, 1]）
     */
    default void feedAudio(float[] samples) {
        // 不支持流式的客户端忽略此方法
    }

    /**
     * 获取当前流式转录的增量文本。
     *
     * <p>可多次调用，每次返回自上次调用以来新增的识别文本（如有）。
     * 流式模型会缓存增量，非流式模型返回 {@code null}。
     *
     * @return 增量文本，不支持流式时返回 {@code null}
     */
    default String getResult() {
        return null;
    }

    /**
     * 完成流式转录，释放状态，返回最终完整文本。
     *
     * <p>调用后需重新调用 {@link #feedAudio} 开始新的转录。
     * 不支持流式的客户端直接委托给 {@link #transcribe()}。
     *
     * @return 最终完整识别文本
     */
    default String complete() {
        return transcribe();
    }

    /**
     * 一次性流式转写便捷方法。
     *
     * <p>内部自动管理 feed/getResult/complete 生命周期，
     * 适用于已知完整音频片段但不需要增量回调的场景。
     *
     * @param samples 完整音频样本（16kHz mono，float [-1, 1]）
     * @return 识别文本
     */
    default String streamingTranscribe(float[] samples) {
        feedAudio(samples);
        String result = getResult();
        complete();
        return result != null ? result : transcribe();
    }

    /**
     * 创建语音识别任务（异步模式）
     *
     * <p>提交任务后立即返回，不等待任务完成。需配合 {@link #queryTask(String)} 轮询结果。
     *
     * @param path 音频文件路径
     * @return 任务 ID，用于后续查询任务状态和结果
     */
    String createTask(Path path);

    /**
     * 查询语音识别任务状态和结果
     *
     * @param taskId 任务 ID，由 {@link #createTask(Path)} 返回
     * @return 任务状态及结果
     */
    AudioResponse queryTask(String taskId);

    /**
     * 关闭客户端，释放底层资源
     */
    @Override
    default void close() {
    }

    /**
     * 获取服务商支持的模型列表
     *
     * @return 可用模型定义列表
     */
    default List<ModelDefinition> models() {
        return List.of();
    }

    /**
     * 查询当前能力下全部可用模型 ID。
     *
     * <p>基于 {@link #models()} 提取模型 ID 列表，供统一能力清单与前端按能力筛选使用。</p>
     *
     * @return 模型 ID 列表
     */
    default List<String> listModels() {
        List<ModelDefinition> defs = models();
        if (defs == null || defs.isEmpty()) {
            return List.of();
        }
        return defs.stream()
                .filter(d -> d != null && d.getId() != null && !d.getId().isBlank())
                .map(ModelDefinition::getId)
                .toList();
    }
}
