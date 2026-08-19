package com.chua.common.support.concurrent.lock;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 锁释放辅助：同步结果立即释放，响应式/异步结果在完成后再释放。
 * @since 4.0.0.42
 */
public final class LockReleaseSupport {

    /** 创建 LockReleaseSupport 实例 */
    private LockReleaseSupport() {
    }

    /**
     * 根据返回值类型决定何时执行 {@code unlock}。
     * <ul>
     *   <li>{@link Mono}/{@link Flux}：{@code doFinally} 后释放（完成/错误/取消）</li>
     *   <li>{@link CompletionStage}/{@link CompletableFuture}：完成后释放</li>
     *   <li>其它：立即释放</li>
     * </ul>
     *
     * @param result 方法返回值
     * @param unlock 释放逻辑
     * @return 原返回值（响应式场景为附加了释放钩子的同一类型流）
     */
    public static Object releaseAfter(Object result, Runnable unlock) {
        switch (result) {
            case null -> {
                unlock.run();
                return null;
            }
            case Mono<?> mono -> {
                return mono.doFinally(signal -> unlock.run());
            }
            case Flux<?> flux -> {
                return flux.doFinally(signal -> unlock.run());
            }
            case CompletableFuture<?> future -> {
                return future.whenComplete((r, e) -> unlock.run());
            }
            case CompletionStage<?> stage -> {
                return stage.whenComplete((r, e) -> unlock.run());
            }
            default -> {
            }
        }
        unlock.run();
        return result;
    }
}
