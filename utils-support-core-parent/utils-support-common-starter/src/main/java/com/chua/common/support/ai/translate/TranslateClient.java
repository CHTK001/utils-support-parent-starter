package com.chua.common.support.ai.translate;

import com.chua.common.support.ai.chat.AuthHeaders;
import com.chua.common.support.pool.PooledObjectClient;
import com.chua.common.support.spi.ServiceProvider;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * AI 翻译客户端接口（翻译门面）
 *
 * <p>提供统一的 AI 翻译调用抽象，语义与 {@link com.chua.common.support.ai.chat.ChatClient}
 * 对齐：实现类通过 SPI 机制按 provider 名称注册，
 * 调用方通过工厂方法获取实例，各服务商的协议差异对调用方透明。
 *
 * <p>链式配置示例：
 * <pre>{@code
 *   String translated = TranslateClient.create("bilibili", "sk-xxx")
 *       .model("Index-Translate-35B-A3B")
 *       .targetLang("英语")
 *       .translateSync("你好，世界。");
 * }</pre>
 *
 * <p>流式调用示例：
 * <pre>{@code
 *   TranslateClient.create("bilibili", "sk-xxx")
 *       .targetLang("英语")
 *       .translate("你好，世界。", response -> {
 *           switch (response.getState()) {
 *               case START     -> System.out.println("开始");
 *               case STREAMING -> System.out.print(response.getContent());
 *               case STOP      -> System.out.println("\n完成");
 *               case ERROR     -> System.err.println(response.getErrorMessage());
 *           }
 *       });
 * }</pre>
 *
 * @author CH
 * @since 2026/10/12
 */
public interface TranslateClient extends AutoCloseable, PooledObjectClient<TranslateClient> {

    /**
     * 通过完整配置创建 AI 翻译客户端
     *
     * @param setting 客户端配置，包含 provider、appKey、baseUrl 等
     * @return TranslateClient 实例
     */
    static TranslateClient create(TranslateClientSetting setting) {
        return ServiceProvider.of(TranslateClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
     * 创建指定 provider 的 AI 翻译客户端
     *
     * @param provider AI 服务商名称
     * @param appKey   API 密钥
     * @return TranslateClient 实例
     */
    static TranslateClient create(String provider, String appKey) {
        return create(TranslateClientSetting.builder()
                .provider(provider).appKey(appKey).build());
    }

    /**
     * 创建指定 provider、密钥和自定义地址的 AI 翻译客户端
     *
     * @param provider AI 服务商名称
     * @param appKey   API 密钥
     * @param baseUrl  自定义 API 地址
     * @return TranslateClient 实例
     */
    static TranslateClient create(String provider, String appKey, String baseUrl) {
        return create(TranslateClientSetting.builder()
                .provider(provider).appKey(appKey).baseUrl(baseUrl).build());
    }

    /**
     * 设置翻译模型名称
     *
     * @param model 模型名称，如 "Index-Translate-35B-A3B"
     * @return 当前客户端实例，支持链式调用
     */
    default TranslateClient model(String model) {
        return this;
    }

    /**
     * 设置源语言
     *
     * <p>待翻译文本的语言，为空时由模型自动检测。
     *
     * @param sourceLanguage 源语言，如 "中文"
     * @return 当前客户端实例，支持链式调用
     */
    default TranslateClient sourceLang(String sourceLanguage) {
        return this;
    }

    /**
     * 设置目标语言
     *
     * @param targetLanguage 目标语言，如 "英语"、"日语"
     * @return 当前客户端实例，支持链式调用
     */
    default TranslateClient targetLang(String targetLanguage) {
        return this;
    }

    /**
     * 设置翻译指令
     *
     * <p>覆盖默认的翻译提示词模板。支持 {@code {text}} 占位符，
     * 会被替换为待翻译文本；不包含占位符时在指令后自动换行拼接文本。
     *
     * @param prompt 翻译指令模板
     * @return 当前客户端实例，支持链式调用
     */
    default TranslateClient prompt(String prompt) {
        return this;
    }

    /**
     * 设置温度参数
     *
     * <p>控制生成文本的随机性，取值范围 [0.0, 2.0]。
     * 翻译场景建议较低值（如 0.3）以保证译文的确定性。
     *
     * @param temperature 温度值
     * @return 当前客户端实例，支持链式调用
     */
    default TranslateClient temperature(double temperature) {
        return this;
    }

    /**
     * 设置最大输出 Token 数
     *
     * @param maxTokens 最大 Token 数量
     * @return 当前客户端实例，支持链式调用
     */
    default TranslateClient maxTokens(int maxTokens) {
        return this;
    }

    /**
     * 设置 Top-P 采样参数
     *
     * @param topP Top-P 值
     * @return 当前客户端实例，支持链式调用
     */
    default TranslateClient topP(double topP) {
        return this;
    }

    /**
     * 设置是否使用流式请求
     *
     * <p>流式（stream=true）逐帧推送译文，适合实时打字机效果；
     * 非流式（stream=false）一次性返回完整结果，兼容性更好，
     * 避免部分中转服务商流式长连接挂起。默认非流式。
     *
     * @param stream true 使用流式，false 使用非流式
     * @return 当前客户端实例，支持链式调用
     */
    default TranslateClient stream(boolean stream) {
        return this;
    }

    /**
     * 流式翻译
     *
     * <p>以流式方式发送翻译请求，每次收到服务端返回的译文片段时回调 consumer。
     * consumer 接收的 {@link TranslateResponse} 对象包含完整的生命周期事件：
     * <ul>
     *   <li>{@link TranslateResponse.State#START} — 请求开始</li>
     *   <li>{@link TranslateResponse.State#STREAMING} — 译文片段</li>
     *   <li>{@link TranslateResponse.State#STOP} — 请求结束</li>
     *   <li>{@link TranslateResponse.State#ERROR} — 请求出错</li>
     * </ul>
     *
     * @param text     待翻译文本
     * @param consumer 流式响应回调，接收 {@link TranslateResponse} 事件对象
     */
    default void translate(String text, Consumer<TranslateResponse> consumer) {
        translate(text, consumer, () -> {
        }, throwable -> {
            throw new RuntimeException(throwable);
        });
    }

    /**
     * 流式翻译（含完成回调和错误回调）
     *
     * <p>默认实现：将同步调用包装为流式事件序列。非流式实现类可直接使用此默认方法，
     * 流式实现类应覆写此方法以提供真正的流式体验。
     *
     * @param text       待翻译文本
     * @param consumer   流式响应回调
     * @param onComplete 完成回调，所有事件发送完毕后执行
     * @param onError    错误回调
     */
    default void translate(String text, Consumer<TranslateResponse> consumer,
                           Runnable onComplete, Consumer<Throwable> onError) {
        try {
            TranslateSyncResponse syncResponse = translateSyncWithResponse(text);
            if (syncResponse != null && syncResponse.getText() != null) {
                consumer.accept(TranslateResponse.builder()
                        .state(TranslateResponse.State.STREAMING)
                        .content(syncResponse.getText())
                        .build());
            }
            consumer.accept(TranslateResponse.builder()
                    .state(TranslateResponse.State.STOP)
                    .usage(syncResponse != null ? syncResponse.getUsage() : null)
                    .build());
            onComplete.run();
        } catch (Throwable e) {
            consumer.accept(TranslateResponse.builder()
                    .state(TranslateResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 同步翻译
     *
     * <p>发送请求并等待完整响应，返回全部译文内容。
     *
     * @param text 待翻译文本
     * @return 完整译文文本
     */
    String translateSync(String text);

    /**
     * 同步翻译（带超时）
     *
     * <p>发送请求并等待完整响应，支持超时控制。
     * 默认实现忽略超时参数，直接调用 {@link #translateSync(String)}。
     *
     * @param text        待翻译文本
     * @param timeoutMillis 超时时间（毫秒），小于等于 0 表示不超时
     * @return 完整译文文本
     */
    default String translateSync(String text, long timeoutMillis) {
        return translateSync(text);
    }

    /**
     * 同步翻译（返回完整响应对象）
     *
     * <p>发送请求并等待完整响应，返回包含译文和用量信息的响应对象。
     * 与 {@link #translateSync(String)} 仅返回纯文本不同，本方法返回的
     * {@link TranslateSyncResponse} 额外携带 Token 用量等计量信息。
     *
     * <p>默认实现基于 {@link #translateSync(String)} 包装，不包含用量信息。
     * 实现类应覆写此方法以提供完整的用量数据。
     *
     * @param text 待翻译文本
     * @return 包含译文和用量信息的完整响应对象
     */
    default TranslateSyncResponse translateSyncWithResponse(String text) {
        String translated = translateSync(text);
        return TranslateSyncResponse.builder()
                .text(translated)
                .build();
    }

    /**
     * 异步翻译（返回包含用量信息的响应对象）
     *
     * <p>通过 CompletableFuture 异步执行同步翻译，返回完整的
     * {@link TranslateSyncResponse} 对象，包含译文和用量信息。
     *
     * @param text 待翻译文本
     * @return 异步任务，完成时返回包含译文和用量信息的响应对象
     */
    default CompletableFuture<TranslateSyncResponse> translateAsync(String text) {
        return CompletableFuture.supplyAsync(() -> translateSyncWithResponse(text));
    }

    /**
     * 解析当前客户端应发送的认证头
     *
     * <p>实现类在拼装出站请求时调用本方法即可，无需自行判断认证方案。
     * 返回 null 表示本次请求不发送任何认证头。
     *
     * @return 认证头键值对；免鉴权或无 Key 时返回 null
     */
    default Map.Entry<String, String> resolveAuthHeader() {
        TranslateClientSetting setting = getSetting();
        if (setting == null) {
            return AuthHeaders.resolve(null, null, null, getApiKey());
        }
        return AuthHeaders.resolve(setting.getAuthScheme(),
                setting.getAuthHeader(), setting.getAuthPrefix(), getApiKey());
    }

    /**
     * 关闭客户端
     *
     * <p>释放底层资源，如 HTTP 连接池等。
     */
    @Override
    default void close() {
    }

    /**
     * 获取客户端配置
     *
     * @return TranslateClientSetting 配置对象
     */
    default TranslateClientSetting getSetting() {
        return null;
    }

    /**
     * 获取当前模型名称
     *
     * @return 模型名称，未设置时返回 null
     */
    default String getModel() {
        return null;
    }

    /**
     * 获取 API Key
     *
     * @return API 密钥
     */
    default String getApiKey() {
        TranslateClientSetting setting = getSetting();
        return setting != null ? setting.getAppKey() : null;
    }

    /**
     * 获取 API Base URL
     *
     * @return API 地址
     */
    default String getBaseUrl() {
        TranslateClientSetting setting = getSetting();
        return setting != null ? setting.getBaseUrl() : null;
    }
}
