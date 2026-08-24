package com.chua.common.support.task.taskrunner;

import com.chua.common.support.concurrent.circuitbreaker.CircuitBreakerFlow;
import com.chua.common.support.utils.ThreadUtils;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeoutException;

/**
 * 运行器提供者模板基类 — 封装单节点执行链的通用组装逻辑。
 *
 * <p>提供三段式执行链包装（由外到内）：</p>
 * <ol>
 *   <li><strong>熔断降级</strong>：{@link CircuitBreakerFlow} 保护，拒绝或失败时走 fallback</li>
 *   <li><strong>超时</strong>：嵌套结构化并发作用域 + withTimeout 约束整个重试序列</li>
 *   <li><strong>重试</strong>：指数退避（200ms 起步、封顶 2s），仅对 Exception 重试</li>
 * </ol>
 *
 * <p>同时提供数据依赖校验与事件发布辅助方法。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class AbstractRunnerProvider {

    /**
     * 组装并执行单个节点：校验数据依赖 → 执行链包装 → 结果写入上下文。
     *
     * @param def     节点定义
     * @param context 运行上下文
     * @param options 执行参数
     * @return 节点结果
     */
    protected TaskResult executeNode(TaskDefinition def, RunnerContext context,
                                     RunnerProvider.ExecutionOptions options) {
        var started = System.currentTimeMillis();
        try {
            var chain = buildChain(def, context, options);
            var value = chain.call();
            context.putResult(def.getId(), value);
            return TaskResult.success(def.getId(), value, System.currentTimeMillis() - started);
        } catch (Throwable t) {
            var cause = unwrap(t);
            return TaskResult.failed(def.getId(), cause, System.currentTimeMillis() - started);
        }
    }

    /**
     * 组装节点执行链：熔断(可选) → 超时(可选) → 重试 → 数据依赖校验 → 业务函数。
     *
     * @param def     节点定义
     * @param context 运行上下文
     * @param options 执行参数
     * @return 可调用执行链
     */
    protected Callable<Object> buildChain(TaskDefinition def, RunnerContext context,
                                          RunnerProvider.ExecutionOptions options) {
        Callable<Object> core = () -> {
            verifyDataDependencies(def, context);
            return def.getAction().apply(context);
        };

        Callable<Object> retried = wrapRetry(core, def.resolveRetry(options.defaultRetry()));
        var effectiveTimeout = def.resolveTimeout(options.defaultTimeout());
        Callable<Object> timed = effectiveTimeout != null
                ? wrapTimeout(retried, effectiveTimeout)
                : retried;

        if (!def.isCircuitBreakerEnabled()) {
            return applyFallbackOnFailure(def, context, timed);
        }

        var flow = CircuitBreakerFlow.of("task-runner:" + def.getId())
                .failureThreshold(def.getFailureThreshold())
                .successThreshold(def.getSuccessThreshold())
                .waitDuration(def.getWaitDuration());
        if (def.hasFallback()) {
            flow.fallback(() -> def.getFallback().apply(context));
        }
        return () -> flow.execute(timed);
    }

    /**
     * 为未启用熔断的节点追加统一的降级兜底：执行异常且定义了 fallback 时，
     * 以降级返回值作为成功结果。
     *
     * @param def     节点定义
     * @param context 运行上下文
     * @param inner   内层执行链
     * @return 包装后的执行链
     */
    private Callable<Object> applyFallbackOnFailure(TaskDefinition def, RunnerContext context,
                                                    Callable<Object> inner) {
        if (!def.hasFallback()) {
            return inner;
        }
        return () -> {
            try {
                return inner.call();
            } catch (Throwable t) {
                return def.getFallback().apply(context);
            }
        };
    }

    /**
     * 重试包装：共 attempts 次尝试，失败间隔按指数退避。
     *
     * @param core     核心逻辑
     * @param attempts 总尝试次数，必须 ≥ 1
     * @return 包装后的执行链
     */
    private Callable<Object> wrapRetry(Callable<Object> core, int attempts) {
        var times = Math.max(attempts, 1);
        return () -> {
            Exception last = null;
            for (var attempt = 0; attempt < times; attempt++) {
                try {
                    return core.call();
                } catch (Exception e) {
                    last = e;
                    if (attempt < times - 1) {
                        ThreadUtils.sleepMillisecondsQuietly(TaskDefinition.backoffMillis(attempt));
                    }
                }
            }
            throw last;
        };
    }

    /**
     * 超时包装：嵌套结构化并发作用域施加整体时限。
     *
     * <p>子任务失败以 {@code FailedException} 呈现，此处解包还原原始异常；
     * 超时以 {@link TimeoutException} 抛出。</p>
     *
     * @param inner    内层执行链
     * @param duration 时限
     * @return 包装后的执行链
     * @throws Exception 执行失败或超时
     */
    private Callable<Object> wrapTimeout(Callable<Object> inner, Duration duration) {
        return () -> {
            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.<Object>awaitAllSuccessfulOrThrow(),
                    cf -> cf.withTimeout(duration))) {
                var subtask = scope.fork(inner::call);
                scope.join();
                return subtask.get();
            } catch (StructuredTaskScope.FailedException e) {
                throw unwrap(e.getCause());
            } catch (TimeoutException e) {
                throw e;
            }
        };
    }

    /**
     * 校验数据依赖的前置节点均已有非 null 结果。
     *
     * @param def     节点定义
     * @param context 运行上下文
     * @throws IllegalStateException 当任一前置节点无有效结果时
     */
    protected void verifyDataDependencies(TaskDefinition def, RunnerContext context) {
        for (var dep : def.getDataDependencies()) {
            if (!context.hasResult(dep)) {
                throw new IllegalStateException(
                        "节点 " + def.getId() + " 的数据依赖 " + dep + " 无有效结果");
            }
        }
    }

    /**
     * 解包运行时包装异常，尽量还原业务原始异常。
     *
     * <p>仅对"裸 RuntimeException 且携带原因"的包装（如熔断器抛出的
     * {@code new RuntimeException(msg, cause)}）逐层解包，其余原样返回。</p>
     *
     * @param t 待解包异常
     * @return 解包后的异常
     */
    protected Throwable unwrap(Throwable t) {
        if (t.getClass() == RuntimeException.class && t.getCause() != null) {
            return unwrap(t.getCause());
        }
        return t;
    }

    /**
     * 向全部监听器发布事件。
     *
     * @param options 执行参数
     * @param event   事件
     */
    protected void emit(RunnerProvider.ExecutionOptions options, RunnerEvent event) {
        for (var listener : options.listeners()) {
            listener.onEvent(event);
        }
    }
}
