package com.chua.common.support.ai.chat.usage;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.lang.datasource.engine.Engine;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 用量持久化 ChatClient 包装器 — 为任意 {@link ChatClient} 添加异步 AiUsage 持久化能力。
 *
 * <p>通过装饰器模式拦截 {@link #chatSyncWithResponse(String)} 调用，
 * 在返回响应后异步将 {@link AiUsage} 通过 Engine ORM 持久化到数据库。
 * 不阻塞调用线程，适用于所有 ChatClient 实现。
 *
 * <p>使用示例：
 * <pre>{@code
 *   Engine engine = Engine.create("jdbc");
 *
 *   // 包装任意 ChatClient
 *   ChatClient client = UsagePersistChatClient.wrap(
 *       ChatClient.create("openai", "sk-xxx"), engine);
 *
 *   // 通过 chatSyncWithResponse 触发异步持久化
 *   ChatSyncResponse resp = client.chatSyncWithResponse("你好");
 *
 *   // 或通过流式 chat（STOP 事件中的 usage 被自动持久化）
 *   client.chat("你好", System.out::print);
 *
 *   // 注意：chatSync() 仅返回文本，不触发持久化。
 *   // 请使用 chatSyncWithResponse() 或 chat() 流式调用。
 *
 *   // 关闭前等待所有待写入完成
 *   client.close();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 *
 * @see AggregateChatClient AggregateChatClient 内置了相同的持久化逻辑，
 *      包装已配置 Engine 的 AggregateChatClient 会导致用量重复写入，请避免。
 */
@Slf4j
public class UsagePersistChatClient implements ChatClient {

    /** 委托客户端 */
    private final ChatClient delegate;
    /** 引擎实例 */
    private final Engine engine;
    /** 待处理异步任务列表 */
    private final List<CompletableFuture<?>> pendingFutures = new CopyOnWriteArrayList<>();

    /**
    * 包装任意 ChatClient，为其添加异步用量持久化能力
    *
    * @param delegate 被包装的 ChatClient 实例（不应是已配置 Engine 的 AggregateChatClient）
    * @param engine   Engine 实例
    * @return 包装后的 ChatClient
    */
    public static UsagePersistChatClient wrap(ChatClient delegate, Engine engine) {
        return new UsagePersistChatClient(delegate, engine);
    }

    /**
     * 创建 UsagePersistChatClient 实例
     * @param delegate delegate
     * @param engine Engine
     */
    private UsagePersistChatClient(ChatClient delegate, Engine engine) {
        this.delegate = delegate;
        this.engine = engine;
    }

    // ======================== 包装的接口方法 ========================

    @Override
    /** ChatSync */
    public String chatSync(String prompt) {
        return delegate.chatSync(prompt);
    }

    @Override
    /** ChatSyncWithResponse */
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        ChatSyncResponse response = delegate.chatSyncWithResponse(prompt);
        persistAsync(response != null ? response.getUsage() : null);
        return response;
    }

    @Override
    /**
     * 对话
     * @param prompt prompt
     * @param consumer consumer
     * @param onComplete onComplete
     * @param onError onError
     */
    public void chat(String prompt, Consumer<ChatResponse> consumer,
                     Runnable onComplete, Consumer<Throwable> onError) {
        delegate.chat(prompt, raw -> {
            consumer.accept(raw);
            // STOP 事件携带用量信息，在此处异步持久化
            if (raw.getState() == ChatResponse.State.STOP) {
                persistAsync(raw.getUsage());
            }
        }, onComplete, onError);
    }

    @Override
    /** ChatAsync */
    public CompletableFuture<ChatSyncResponse> chatAsync(String prompt) {
        return delegate.chatAsync(prompt)
                .thenApply(response -> {
                    persistAsync(response != null ? response.getUsage() : null);
                    return response;
                });
    }

    @Override
    /** Models */
    public List<ModelDefinition> models() {
        return delegate.models();
    }

    // ======================== 链式方法 ========================

    @Override
    /** Provider */
    public ChatClient provider(String provider) {
        delegate.provider(provider);
        return this;
    }

    @Override
    /** Model */
    public ChatClient model(String model) {
        delegate.model(model);
        return this;
    }

    @Override
    /** System */
    public ChatClient system(String system) {
        delegate.system(system);
        return this;
    }

    @Override
    /** Temperature */
    public ChatClient temperature(double temperature) {
        delegate.temperature(temperature);
        return this;
    }

    @Override
    /** 最大值Tokens */
    public ChatClient maxTokens(int maxTokens) {
        delegate.maxTokens(maxTokens);
        return this;
    }

    @Override
    /** 添加Image */
    public ChatClient addImage(String imageUrl) {
        delegate.addImage(imageUrl);
        return this;
    }

    @Override
    /** 添加UserHistory */
    public ChatClient addUserHistory(String content) {
        delegate.addUserHistory(content);
        return this;
    }

    @Override
    /** 添加AssistantHistory */
    public ChatClient addAssistantHistory(String content) {
        delegate.addAssistantHistory(content);
        return this;
    }

    @Override
    /** History */
    public ChatClient history(List<ChatMessage> messages) {
        delegate.history(messages);
        return this;
    }

    @Override
    /** 添加Attachment */
    public ChatClient addAttachment(String name, byte[] data, String mimeType) {
        delegate.addAttachment(name, data, mimeType);
        return this;
    }

    @Override
    /** 添加AttachmentUrl */
    public ChatClient addAttachmentUrl(String name, String url, String mimeType) {
        delegate.addAttachmentUrl(name, url, mimeType);
        return this;
    }

    @Override
    /** Session */
    public ChatClient session(String sessionId) {
        delegate.session(sessionId);
        return this;
    }

    @Override
    /** NewChat */
    public ChatClient newChat() {
        delegate.newChat();
        return this;
    }

    // ======================== 持久化控制 ========================

    /**
     * 等待所有异步用量写入完成
     */
    public void flush() {
        List<CompletableFuture<?>> pending = List.copyOf(pendingFutures);
        if (pending.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                    .join();
        } catch (Exception e) {
            log.warn("[UsagePersistChatClient] flush failed: {}", e.getMessage());
        }
    }

    /**
     * 从外部 SPI 同步用量到当前 Engine
     *
     * <p>将外部数据源（如 UsageParser 解析的本地工具用量）批量写入 Engine 持久化表。</p>
     *
     * @param externalUsage 外部来源的用量数据列表
     */
    public void syncUsage(List<AiUsage> externalUsage) {
        if (externalUsage == null || externalUsage.isEmpty() || engine == null) {
            return;
        }
        for (AiUsage usage : externalUsage) {
            if (usage == null) {
                continue;
            }
            if (usage.getProvider() == null) {
                usage.setProvider("external-sync");
            }
            if (usage.getRequestId() == null) {
                usage.setRequestId("sync-" + System.nanoTime());
            }
            persistAsync(usage);
        }
        log.info("[UsagePersistChatClient] 从外部同步 {} 条用量到 Engine", externalUsage.size());
    }

    @Override
    /** 关闭 */
    public void close() {
        flush();
        delegate.close();
    }

    // ======================== 内部方法 ========================

    /**
     * PersistAsync
     * @param usage 方法入参 usage
     */
    private void persistAsync(AiUsage usage) {
        if (usage == null || engine == null) {
            return;
        }
        CompletableFuture<AiUsageRecord> future = AiUsageRecord.from(usage).asyncSave(engine);
        pendingFutures.add(future);
        future.whenComplete((r, t) -> {
            if (pendingFutures.remove(future) && t != null) {
                log.warn("[UsagePersistChatClient] async persist usage failed: {}", t.getMessage());
            }
        });
    }

    @Override
    public ChatClientSetting getSetting() {
        return delegate.getSetting();
    }

    @Override
    public String getModel() {
        return delegate.getModel();
    }
}
