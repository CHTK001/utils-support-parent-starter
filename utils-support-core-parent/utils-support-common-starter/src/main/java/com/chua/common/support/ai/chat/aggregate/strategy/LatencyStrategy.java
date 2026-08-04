package com.chua.common.support.ai.chat.aggregate.strategy;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.aggregate.FailoverTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.NullUnmarked;

/**
 * 低延迟策略 — 并行发起所有请求，取最先成功返回的。
 *
 * <p>适用于对延迟敏感的场景。⚠️ 会同时消耗多个 Key 的配额。
 * 同步使用并行竞速，流式回退到 {@link FailoverTemplate}。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@SuppressWarnings("NullAway")
@NullUnmarked
public class LatencyStrategy implements RouterStrategy {

    private static final long MAX_WAIT_MS = 60_000;

    @Override
    public WeightedClient select(List<WeightedClient> clients, String prompt) {
        // LatencyStrategy always returns the first — actual logic is in executeSync
        if (clients.isEmpty()) throw new IllegalArgumentException("No clients available");
        return clients.get(0);
    }

    @Override
    public String executeSync(List<WeightedClient> clients, String prompt,
                              Consumer<AiUsage> usageCallback) throws Exception {
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No clients configured for latency strategy");
        }

        List<CompletableFuture<Result>> futures = new ArrayList<>();

        for (WeightedClient wc : clients) {
            CompletableFuture<Result> future = CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                try {
                    ChatSyncResponse resp = wc.client().chatSyncWithResponse(prompt);
                    long elapsed = System.currentTimeMillis() - start;
                    if (usageCallback != null && resp != null) {
                        usageCallback.accept(resp.usage());
                    }
                    return new Result(wc, resp != null ? resp.text() : "", elapsed, null);
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    return new Result(wc, null, elapsed, e);
                }
            });
            futures.add(future);
        }

        // Wait for first success via anyOf
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                // Poll with small timeout to avoid tight loop
                try {
                    CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                            .get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // continue polling
                }

                for (CompletableFuture<Result> f : futures) {
                    if (f.isDone()) {
                        Result r = f.get();
                        if (r.error == null) {
                            log.debug("[Latency] winner={} ({}ms)", r.client.provider(), r.elapsed);
                            cancelOthers(futures, f);
                            return r.text;
                        }
                    }
                }

                // All done and all failed
                if (futures.stream().allMatch(CompletableFuture::isDone)) {
                    break;
                }
            }
        } finally {
            cancelAll(futures);
        }

        throw new RuntimeException("All clients failed in latency strategy");
    }

    @Override
    public void executeStream(List<WeightedClient> clients, String prompt,
                              Consumer<ChatResponse> consumer) throws Exception {
        log.warn("[Latency] Streaming not supported, falling back to failover");
        FailoverTemplate.executeStream(this, clients, prompt, consumer);
    }

    private void cancelOthers(List<CompletableFuture<Result>> futures, CompletableFuture<Result> keep) {
        for (CompletableFuture<Result> f : futures) {
            if (f != keep && !f.isDone()) f.cancel(true);
        }
    }

    private void cancelAll(List<CompletableFuture<Result>> futures) {
        for (CompletableFuture<Result> f : futures) {
            if (!f.isDone()) f.cancel(true);
        }
    }

    private record Result(WeightedClient client, String text, long elapsed, Exception error) {
    }
}