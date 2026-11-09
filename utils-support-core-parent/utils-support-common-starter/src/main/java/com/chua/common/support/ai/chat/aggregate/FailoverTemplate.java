package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.aggregate.strategy.RouterStrategy;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 公共故障转移模板 — 包装任意 {@link RouterStrategy}，自动处理重试和用量记录。
 *
 * <p>所有扁平策略（failover / round_robin / weighted / cost）共享此模板，
 * 无需各自实现 try-catch 循环。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class FailoverTemplate {

    /**
     * 创建 FailoverTemplate 实例
     */
    private FailoverTemplate() {
    }

    /**
     * 放弃卡顿客户端后，等待其底层请求真正结束（关闭连接）的最长时间。
     */
    private static final long ABANDON_WAIT_MS = 1500L;

    /**
     * 流式竞速线程池的并发上限。
     *
     * <p>候选客户端数量由上游选择器给出，实际并发不会超过候选数；
     * 设置上限可避免候选集合异常膨胀时线程数无界增长。
     */
    private static final int MAX_STREAM_CANDIDATES = 64;

    /**
     * 执行带故障转移的同步对话
     *
     * @param selector      客户端选择器（策略的 select 方法）
     * @param clients       候选客户端列表
     * @param prompt        用户输入
     * @param usageCallback 用量回调，每次成功调用时触发（可为 null）
     * @return 响应文本
     * @throws Exception 全部客户端失败时抛出
     */
    public static String executeSync(
            RouterStrategy selector,
            List<RouterStrategy.WeightedClient> clients,
            String prompt,
            Consumer<AiUsage> usageCallback) throws Exception {

        List<RouterStrategy.WeightedClient> remaining = Wraps.list(clients);
        if (remaining.isEmpty()) {
            throw new IllegalArgumentException("No clients configured");
        }
        // 上次失败后仍在冷却的客户端先跳过；全部都在冷却时放行全部（半开），避免整体不可用
        remaining = ClientCooldownRegistry.available(remaining);

        Exception lastError = null;
        int attempt = 0;

        while (!remaining.isEmpty()) {
            RouterStrategy.WeightedClient wc;
            try {
                wc = selector.select(remaining, prompt);
            } catch (Exception e) {
                log.warn("[FailoverTemplate] select() failed on attempt {}: {}", attempt + 1, e.getMessage(), e);
                break;
            }

            // 从剩余列表中移除已选择的客户端，避免重复选择
            remaining.remove(wc);

            long start = System.currentTimeMillis();
            attempt++;

            try {
                // 使用 chatSyncWithResponse 获取完整响应（含 AiUsage）
                ChatSyncResponse resp = wc.client().chatSyncWithResponse(prompt);
                long elapsed = System.currentTimeMillis() - start;

                log.debug("[FailoverTemplate] {} succeeded in {}ms (attempt {})",
                        wc.provider(), elapsed, attempt);

                // 记录用量
                if (usageCallback != null && resp != null) {
                    usageCallback.accept(resp.usage());
                }

                ClientCooldownRegistry.recordSuccess(wc);
                return resp != null ? resp.text() : "";
            } catch (Exception e) {
                lastError = e;
                ClientCooldownRegistry.recordFailure(wc, e);
                long elapsed = System.currentTimeMillis() - start;
                log.warn("[FailoverTemplate] {} failed after {}ms (attempt {}): {}",
                        wc.provider(), elapsed, attempt, e.getMessage(), e);
            }
        }

        throw new RuntimeException("All " + clients.size() + " client(s) failed", lastError);
    }

    /**
     * 执行带故障转移的流式对话
     *
     * @param selector  客户端选择器
     * @param clients   候选客户端列表
     * @param prompt    用户输入
     * @param consumer  流式响应回调
     * @throws Exception 全部客户端失败时抛出
     */
    public static void executeStream(
            RouterStrategy selector,
            List<RouterStrategy.WeightedClient> clients,
            String prompt,
            Consumer<ChatResponse> consumer) throws Exception {

        List<RouterStrategy.WeightedClient> remaining = Wraps.list(clients);
        if (remaining.isEmpty()) {
            throw new IllegalArgumentException("No clients configured");
        }
        // 上次失败后仍在冷却的客户端先跳过；全部都在冷却时放行全部（半开），避免整体不可用
        remaining = ClientCooldownRegistry.available(remaining);

        Exception lastError = null;

        // 每个候选客户端在独立线程执行，主线程只等待“首包/错误”，从而能对“慢但不报错”的密钥快速切换
// [P3C C1 豁免] 场景化创建线程，需文档化
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                0,
                MAX_STREAM_CANDIDATES,
                30L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
// [P3C C1 豁免] 场景化创建线程，需文档化
                    Thread t = new Thread(r, "failover-stream");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            while (!remaining.isEmpty()) {
                final RouterStrategy.WeightedClient wc;
                try {
                    wc = selector.select(remaining, prompt);
                } catch (Exception e) {
                    break;
                }
                remaining.remove(wc);

                final CountDownLatch firstSignal = new CountDownLatch(1);
                final CountDownLatch finished = new CountDownLatch(1);
                final AtomicBoolean started = new AtomicBoolean(false);
                final AtomicBoolean abandoned = new AtomicBoolean(false);
                final AtomicReference<Throwable> taskError = new AtomicReference<>(null);

                // 包装回调：首个正文/思考片段或错误到达即放行；被放弃后丢弃该客户端的全部回调
                Consumer<ChatResponse> wrapper = response -> {
                    if (abandoned.get()) {
                        return;
                    }
                    boolean hasContent = (response.getContent() != null && !response.getContent().isEmpty())
                            || (response.getReasoningContent() != null && !response.getReasoningContent().isEmpty());
                    if (hasContent && started.compareAndSet(false, true)) {
                        firstSignal.countDown();
                    } else if (response.getState() == ChatResponse.State.ERROR) {
                        firstSignal.countDown();
                    }
                    try {
                        consumer.accept(response);
                    } catch (Throwable t) {
                        taskError.compareAndSet(null, t);
                    }
                };

                final Future<?> future = pool.submit(() -> {
                    try {
                        wc.client().chat(prompt, wrapper);
                    } catch (Throwable t) {
                        taskError.compareAndSet(null, t);
                    } finally {
                        finished.countDown();
                    }
                });

                long firstTokenTimeoutMs = FailoverTimeouts.firstTokenTimeoutMs();
                boolean signaled = firstSignal.await(firstTokenTimeoutMs, TimeUnit.MILLISECONDS);
                if (!signaled) {
                    // 首包超时：判定该密钥卡顿，关闭并放弃其全部输出，切换下一密钥
                    abandoned.set(true);
                    try {
                        wc.client().close();
                    } catch (Exception e) {
                        log.debug("[failover] 关闭客户端失败，已忽略: {}", e.getMessage());
                    }
                    future.cancel(true);
                    try {
                        finished.await(ABANDON_WAIT_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.debug("[failover] 等待被中断: {}", e.getMessage());
                    }
                    lastError = new java.util.concurrent.TimeoutException(
                            wc.provider() + " 首包超时(" + firstTokenTimeoutMs + "ms)");
                    ClientCooldownRegistry.recordFailure(wc, lastError);
                    log.warn("[FailoverTemplate] {} 首包 {}ms 未返回，切换下一客户端",
                            wc.provider(), firstTokenTimeoutMs);
                    continue;
                }

                // 已收到信号：等待任务彻底结束（正常流式会等到整段回复完成）
                finished.await(FailoverTimeouts.completionWaitSeconds(), TimeUnit.SECONDS);
                Throwable t = taskError.get();
                if (t == null) {
                    ClientCooldownRegistry.recordSuccess(wc);
                    return;
                }
                if (started.get()) {
                    // 开始输出后才失败：内容已部分下发，无法干净切换，直接抛出以免重复/错乱
                    // 此时失败可能来自调用方回调或网络抖动，无法归因到 Key，因此不进入冷却
                    if (t instanceof Exception e) {
                        throw e;
                    }
                    throw new RuntimeException(t);
                }
                // 首包前就出错（401/429/连接失败等）：快速切换下一密钥，不浪费等待
                lastError = (t instanceof Exception e) ? e : new RuntimeException(t);
                ClientCooldownRegistry.recordFailure(wc, lastError);
                log.warn("[FailoverTemplate] {} 首包前失败，切换下一客户端: {}",
                        wc.provider(), t.getMessage(), t);
            }
        } finally {
            pool.shutdownNow();
        }

        throw new RuntimeException("All " + clients.size() + " client(s) stream failed", lastError);
    }}