package com.chua.common.support.ai.audio;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.pool.PooledObjectClient;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;

/**
* AI 文字转语音（TTS）客户端接口。
*
* <p>提供统一的语音合成服务抽象，支持同步合成和异步任务两种模式。
* 实现类通过 SPI 机制按 provider 名称注册，调用方通过工厂方法获取实例。
*
* <p>同步合成示例：
* <pre>{@code
*   byte[] wav = TextToAudioClient.create("openai-tts", "sk-xxx")
*       .model("tts-1")
*       .voice("alloy")
*       .synthesize("你好世界");
* }</pre>
*
* <p>异步任务示例：
* <pre>{@code
*   String taskId = TextToAudioClient.create("piper", "")
*       .model("zh_CN-huayan-medium")
*       .createTask("复杂长文本，由服务端排队合成");
*
*   // 轮询查询任务结果
*   TextToAudioResponse resp = client.queryTask(taskId);
*   if (resp.getStatus() == TextToAudioResponse.Status.SUCCESS) {
*       byte[] wav = resp.getAudioBytes();
*   }
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public interface TextToAudioClient extends AutoCloseable, PooledObjectClient<TextToAudioClient> {

    /**
    * 创建指定 provider 的 TTS 客户端
    *
    * @param provider AI 服务商名称，如 "openai-tts"、"edge-tts"、"piper" 等
    * @param apiKey   API 密钥（本地 ONNX 模型可留空）
    * @return TextToAudioClient 实例
     */
    static TextToAudioClient create(String provider, String apiKey) {
        return ServiceProvider.of(TextToAudioClient.class)
                .getNewExtension(provider, TextToAudioClientSetting.builder()
                        .provider(provider).appKey(apiKey).build());
    }

    /**
    * 通过完整配置创建 TTS 客户端
    *
    * @param setting 客户端配置
    * @return TextToAudioClient 实例
     */
    static TextToAudioClient create(TextToAudioClientSetting setting) {
        return ServiceProvider.of(TextToAudioClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
    * 创建指定 provider 和自定义地址的 TTS 客户端
    *
    * @param provider AI 服务商名称
    * @param apiKey   API 密钥
    * @param baseUrl  自定义 API 基地址
    * @return TextToAudioClient 实例
     */
    static TextToAudioClient create(String provider, String apiKey, String baseUrl) {
        return ServiceProvider.of(TextToAudioClient.class)
                .getNewExtension(provider, TextToAudioClientSetting.builder()
                        .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    /**
    * 设置 AI 服务商
    *
    * @param provider 服务商名称
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient provider(String provider) {
        return this;
    }

    /**
    * 设置模型名称
    *
    * @param model 模型名称，如 "tts-1"、"tts-1-hd"、"piper-zh_CN-huayan-medium"
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient model(String model) {
        return this;
    }

    /**
    * 设置发音人
    *
    * <p>不同 provider 拥有不同 voice 集合：
    * <ul>
    *   <li>OpenAI：alloy / echo / fable / onyx / nova / shimmer</li>
    *   <li>Edge：zh-CN-XiaoxiaoNeural / en-US-JennyNeural ...</li>
    *   <li>Piper：通常为模型自带 voice id</li>
    * </ul>
    *
    * @param voice 发音人 ID
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient voice(String voice) {
        return this;
    }

    /**
    * 设置合成语言
    *
    * <p>ISO 639-1 语言代码，如 "zh"、"en"，
    * 留空则由模型从文本自动推断。
    *
    * @param language 语言代码
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient language(String language) {
        return this;
    }

    /**
    * 设置输出音频格式
    *
    * <p>常见值："wav"、"mp3"、"opus"、"pcm"、"flac"。
    * 留空则使用 provider 默认值。
    *
    * @param format 音频格式
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient format(String format) {
        return this;
    }

    /**
    * 设置语速倍率
    *
    * <p>1.0 表示原速，0.5 半速，2.0 倍速。
    * 范围与 provider 相关，通常 0.25 ~ 4.0。
    *
    * @param speed 语速倍率
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient speed(Double speed) {
        return this;
    }

    /**
    * 设置采样率
    *
    * <p>常见值：16000、22050、24000、44100、48000。
    * 本地 ONNX 模型通常固定为训练时的采样率。
    *
    * @param sampleRate 采样率（Hz）
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient sampleRate(Integer sampleRate) {
        return this;
    }

    /**
    * 设置温度
    *
    * <p>采样温度，0 表示最确定（贪心），越高结果越发散。
    * 大多数 TTS 服务固定为 0。
    *
    * @param temperature 温度
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient temperature(Double temperature) {
        return this;
    }

    /**
    * 设置随机种子
    *
    * @param seed 随机种子
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient seed(Long seed) {
        return this;
    }

    /**
    * 设置要合成的文本
    *
    * @param text 输入文本
    * @return 当前客户端实例，支持链式调用
     */
    default TextToAudioClient text(String text) {
        return this;
    }

    /**
    * 同步合成语音
    *
    * <p>阻塞等待服务端返回完整音频数据。
    *
    * @param text 要合成的文本
    * @return 音频字节（格式由 {@link #format(String)} 指定，默认为 provider 默认）
     */
    byte[] synthesize(String text);

    /**
    * 同步合成语音（使用已配置的 text）
    *
    * @return 音频字节
     */
    default byte[] synthesize() {
        return synthesize(null);
    }

    /**
    * 创建 TTS 任务（异步模式）
    *
    * <p>提交任务后立即返回，不等待任务完成。需配合 {@link #queryTask(String)} 轮询结果。
    *
    * @param text 要合成的文本
    * @return 任务 ID，用于后续查询任务状态和结果
     */
    String createTask(String text);

    /**
    * 查询 TTS 任务状态和结果
    *
    * @param taskId 任务 ID，由 {@link #createTask(String)} 返回
    * @return 任务状态及结果
     */
    TextToAudioResponse queryTask(String taskId);

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
