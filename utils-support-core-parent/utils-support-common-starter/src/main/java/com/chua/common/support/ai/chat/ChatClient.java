package com.chua.common.support.ai.chat;

import com.chua.common.support.ai.chat.usage.UsagePersistChatClient;
import com.chua.common.support.ai.probe.ProbeProgress;
import com.chua.common.support.ai.probe.ProbeReport;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * AI 对话客户端接口
 *
 * <p>提供统一的 AI 大模型对话调用抽象，支持同步、异步和流式三种调用方式。
 * 实现类通过 SPI 机制按 provider 名称注册，调用方通过工厂方法获取实例。
 *
 * <p>链式配置示例：
 * <pre>{@code
 *   String answer = ChatClient.create("openai", "sk-xxx")
 *       .model("gpt-4")
 *       .temperature(0.7)
 *       .system("你是一名助手")
 *       .chatSync("你好");
 * }</pre>
 *
 * <p>流式调用示例：
 * <pre>{@code
 *   ChatClient.create("openai", "sk-xxx")
 *       .model("gpt-4")
 *       .chat("讲个故事", response -> {
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
 * @since 2026/07/15
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public interface ChatClient extends AutoCloseable {

    /**
     * 通过完整配置创建 AI 对话客户端
     *
     * @param setting 客户端配置，包含 provider、apiKey、baseUrl 等
     * @return ChatClient 实例
     */
    static ChatClient create(ChatClientSetting setting) {
        return ServiceProvider.of(ChatClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
     * 创建指定 provider 的 AI 对话客户端
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @return ChatClient 实例
     */
    static ChatClient create(String provider, String apiKey) {
        return create(ChatClientSetting.builder()
                .provider(provider).appKey(apiKey).build());
    }

    /**
     * 创建指定 provider、密钥和自定义地址的 AI 对话客户端
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @param baseUrl  自定义 API 地址
     * @return ChatClient 实例
     */
    static ChatClient create(String provider, String apiKey, String baseUrl) {
        return create(ChatClientSetting.builder()
                .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    /**
     * 包装指定 ChatClient，添加异步用量持久化能力
     *
     * <p>每次 {@link #chatSyncWithResponse(String)} 调用完成后，
     * 自动通过 Engine ORM 异步持久化 {@code AiUsage}，不阻塞调用线程。
     *
     * @param client 待包装的 ChatClient 实例
     * @param engine Engine 实例
     * @return 包装后的 ChatClient
     */
    static ChatClient withUsagePersistence(ChatClient client, Engine engine) {
        return UsagePersistChatClient.wrap(client, engine);
    }

    /**
     * 设置 AI 服务商
     *
     * @param provider 服务商名称
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称
     *
     * @param model 模型名称，如 "gpt-4"、"deepseek-chat"
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient model(String model) {
        return this;
    }

    /**
     * 设置系统提示词
     *
     * @param system 系统提示词内容
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient system(String system) {
        return this;
    }

    /**
     * 设置温度参数
     *
     * <p>控制生成文本的随机性，取值范围 [0.0, 2.0]。
     * 值越低输出越确定，值越高输出越多样。
     *
     * @param temperature 温度值
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient temperature(double temperature) {
        return this;
    }

    /**
     * 设置最大输出 Token 数
     *
     * @param maxTokens 最大 Token 数量
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient maxTokens(int maxTokens) {
        return this;
    }

    /**
     * 添加图片附件
     *
     * <p>仅多模态模型支持图片输入。
     *
     * @param imageUrl 图片 URL 或 Base64 数据 URI
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addImage(String imageUrl) {
        return this;
    }

    /**
     * 添加用户历史消息
     *
     * <p>将用户消息追加到对话历史中，用于多轮对话上下文。
     *
     * @param content 消息内容
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addUserHistory(String content) {
        return this;
    }

    /**
     * 添加助手历史消息
     *
     * <p>将助手消息追加到对话历史中，用于多轮对话上下文。
     *
     * @param content 消息内容
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addAssistantHistory(String content) {
        return this;
    }

    /**
     * 添加对话历史
     *
     * <p>一次性设置完整的对话历史消息列表，覆盖之前添加的历史。
     * 用于多轮对话时传递完整上下文。
     *
     * @param messages 对话历史消息列表，按时间正序排列
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient history(List<ChatMessage> messages) {
        return this;
    }

    /**
     * 添加文件附件
     *
     * <p>上传文件作为当前对话的附件，供多模态模型解析。
     * 支持图片、PDF、Word、代码文件等多种格式。
     *
     * @param name     文件名
     * @param data     文件字节数据
     * @param mimeType MIME 类型，如 "image/png"、"application/pdf"
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addAttachment(String name, byte[] data, String mimeType) {
        return this;
    }

    /**
     * 添加远程文件附件
     *
     * <p>通过 URL 引用远程文件作为附件，不直接上传字节数据。
     *
     * @param name     文件名
     * @param url      文件访问地址
     * @param mimeType MIME 类型
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        return this;
    }

    /**
     * 设置会话 ID
     *
     * <p>用于在多轮对话中关联同一会话的上下文。
     *
     * @param sessionId 会话标识
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient session(String sessionId) {
        return this;
    }

    /**
     * 开启新会话
     *
     * <p>清空当前对话历史，开始一个新的对话。
     *
     * @return 当前客户端实例，支持链式调用
     */
    default ChatClient newChat() {
        return this;
    }

    /**
     * 流式对话
     *
     * <p>以流式方式发送对话请求，每次收到服务端返回的内容片段时回调 consumer。
     * consumer 接收的 {@link ChatResponse} 对象包含完整的生命周期事件：
     * <ul>
     *   <li>{@link ChatResponse.State#START} — 请求开始</li>
     *   <li>{@link ChatResponse.State#STREAMING} — 内容片段</li>
     *   <li>{@link ChatResponse.State#STOP} — 请求结束</li>
     *   <li>{@link ChatResponse.State#ERROR} — 请求出错</li>
     * </ul>
     *
     * @param prompt   用户输入
     * @param consumer 流式响应回调，接收 {@link ChatResponse} 事件对象
     */
    default void chat(String prompt, Consumer<ChatResponse> consumer) {
        chat(prompt, consumer, () -> {
        }, throwable -> {
            throw new RuntimeException(throwable);
        });
    }

    /**
     * 流式对话（含完成回调和错误回调）
     *
     * <p>默认实现：将同步调用包装为流式事件序列。非流式实现类可直接使用此默认方法，
     * 流式实现类应覆写此方法以提供真正的流式体验。
     *
     * @param prompt      用户输入
     * @param consumer    流式响应回调
     * @param onComplete  完成回调，所有事件发送完毕后执行
     * @param onError     错误回调
     */
    default void chat(String prompt, Consumer<ChatResponse> consumer,
                      Runnable onComplete, Consumer<Throwable> onError) {
        try {
            ChatSyncResponse syncResponse = chatSyncWithResponse(prompt);
            if (syncResponse != null && syncResponse.getText() != null) {
                consumer.accept(ChatResponse.builder()
                        .state(ChatResponse.State.STREAMING)
                        .content(syncResponse.getText())
                        .build());
            }
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.STOP)
                    .usage(syncResponse != null ? syncResponse.getUsage() : null)
                    .build());
            onComplete.run();
        } catch (Throwable e) {
            consumer.accept(ChatResponse.builder()
                    .state(ChatResponse.State.ERROR)
                    .errorMessage(e.getMessage())
                    .build());
            onError.accept(e);
        }
    }

    /**
     * 同步对话
     *
     * <p>发送请求并等待完整响应，返回全部文本内容。
     *
     * @param prompt 用户输入
     * @return 完整响应文本
     */
    String chatSync(String prompt);

    /**
     * 同步对话（带超时）
     *
     * <p>发送请求并等待完整响应，支持超时控制。
     * 默认实现忽略超时参数，直接调用 {@link #chatSync(String)}。
     *
     * @param prompt         用户输入
     * @param timeoutMillis  超时时间（毫秒），小于等于 0 表示不超时
     * @return 完整响应文本
     */
    default String chatSync(String prompt, long timeoutMillis) {
        return chatSync(prompt);
    }

    /**
     * 同步对话（返回完整响应对象）
     *
     * <p>发送请求并等待完整响应，返回包含文本内容和用量信息的响应对象。
     * 与 {@link #chatSync(String)} 仅返回纯文本不同，本方法返回的
     * {@link ChatSyncResponse} 额外携带 Token 用量、费用等计量信息。
     *
     * <p>默认实现基于 {@link #chatSync(String)} 包装，不包含用量信息。
     * 实现类应覆写此方法以提供完整的用量数据。
     *
     * @param prompt 用户输入
     * @return 包含文本和用量信息的完整响应对象
     */
    default ChatSyncResponse chatSyncWithResponse(String prompt) {
        String text = chatSync(prompt);
        return ChatSyncResponse.builder()
                .text(text)
                .build();
    }

    /**
     * 异步对话（返回包含用量信息的响应对象）
     *
     * <p>通过 CompletableFuture 异步执行同步对话，返回完整的
     * {@link ChatSyncResponse} 对象，包含文本内容和用量信息。
     *
     * @param prompt 用户输入
     * @return 异步任务，完成时返回包含文本和用量信息的响应对象
     */
    default CompletableFuture<ChatSyncResponse> chatAsync(String prompt) {
        return CompletableFuture.supplyAsync(() -> chatSyncWithResponse(prompt));
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
     * 获取客户端权重（用于加权路由策略）
     *
     * <p>默认权重为 1，可由实现类覆盖以提供自定义权重。
     *
     * @return 权重值，必须大于等于 0
     */
    default int weight() {
        return 1;
    }

    /**
     * 获取服务商支持的模型列表
     *
     * <p>调用 {@code GET {baseUrl}/v1/models} 接口查询可用模型。
     * 返回包含模型 ID、名称、描述、能力等信息的 {@link ModelDefinition} 列表。
     * 默认返回空列表，子类可按需覆写。
     *
     * @return 可用模型定义列表
     */
    default List<ModelDefinition> models() {
        return List.of();
    }

    /**
     * 获取模型定价信息。
     *
     * <p>返回包含单价信息的模型定义列表，可用于费用估算和成本对比。
     * 默认返回空列表，实现类可按需覆写。
     *
     * @return 模型定价列表
     */
    default List<ModelDefinition> modelPricing() {
        return List.of();
    }

    /**
     * 执行 AI 中转站真伪探测。
     *
     * <p>基于多维度交叉验证策略，探测 OpenAI 兼容 API 中转站背后真实使用的模型。
     * 返回包含各维度探测结果、综合置信度和最终判词的 {@link ProbeReport}。
     * 默认抛出 {@link UnsupportedOperationException}，支持探测的实现类（如 {@link OpenAiChatClient}）
     * 应覆写此方法以提供真实探测能力。
     *
     * @return 真伪探测综合报告
     * @throws UnsupportedOperationException 当前实现不支持探测功能
     */
    default ProbeReport probe() {
        throw new UnsupportedOperationException("当前 ChatClient 实现不支持探测功能");
    }
}