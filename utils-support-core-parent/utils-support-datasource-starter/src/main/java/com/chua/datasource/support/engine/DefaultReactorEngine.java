package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 响应式引擎默认实现，将同步 {@link Engine} 包装为 Reactor 响应式。
 *
 * <p>所有阻塞调用通过 {@link Schedulers#boundedElastic()} 调度执行，
 * 避免阻塞 Reactor 事件循环线程。Lambda 链式查询/更新/删除复用
 * {@link ReactorLambdaQueryWrapper} / {@link ReactorLambdaUpdateWrapper} /
 * {@link ReactorLambdaDeleteWrapper}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultReactorEngine implements ReactorEngine {

    /**
     * 被包装的同步引擎
     */
    protected final Engine delegate;

    /**
     * 用同步引擎构造响应式包装。
     *
     * @param delegate 同步引擎
     */
    public DefaultReactorEngine(Engine delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Engine must not be null");
        }
        this.delegate = delegate;
    }

    /**
     * 获取被包装的同步引擎。
     *
     * @return 同步引擎
     */
    public Engine getDelegate() {
        return delegate;
    }

    @Override
    public <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new ReactorLambdaQueryWrapper<>(delegate, entityClass);
    }

    @Override
    public <T> ReactorLambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new ReactorLambdaUpdateWrapper<>(delegate, entityClass);
    }

    @Override
    public <T> ReactorLambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new ReactorLambdaDeleteWrapper<>(delegate, entityClass);
    }

    @Override
    public Flux<Map<String, Object>> query(String sql, Object... params) {
        SqlExecutor executor = delegate.getExecutor();
        if (executor == null) {
            return Flux.error(new UnsupportedOperationException("当前引擎不支持 SQL 查询: " + delegate.getClass().getName()));
        }
        return Mono.fromCallable(() -> executor.query(sql, params))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(list -> Flux.fromIterable(list));
    }

    @Override
    public <T> Flux<T> query(String sql, Class<T> rowType, Object... params) {
        SqlExecutor executor = delegate.getExecutor();
        if (executor == null) {
            return Flux.error(new UnsupportedOperationException("当前引擎不支持 SQL 查询: " + delegate.getClass().getName()));
        }
        return Mono.fromCallable(() -> executor.query(sql, rowType, params))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(list -> Flux.fromIterable(list));
    }

    @Override
    public Mono<Integer> execute(String sql, Object... params) {
        SqlExecutor executor = delegate.getExecutor();
        if (executor == null) {
            return Mono.error(new UnsupportedOperationException("当前引擎不支持数据操作: " + delegate.getClass().getName()));
        }
        return Mono.fromCallable(() -> executor.execute(sql, params))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<Integer> batch(String sql, List<Object[]> batchParams) {
        SqlExecutor executor = delegate.getExecutor();
        if (executor == null) {
            return Flux.error(new UnsupportedOperationException("当前引擎不支持批量操作: " + delegate.getClass().getName()));
        }
        return Mono.fromCallable(() -> executor.batch(sql, batchParams))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(results -> {
                    Integer[] boxed = new Integer[results.length];
                    for (int i = 0; i < results.length; i++) {
                        boxed[i] = results[i];
                    }
                    return Flux.fromArray(boxed);
                });
    }
}
