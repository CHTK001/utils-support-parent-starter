package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.wrapper.EngineDeleteWrapper;
import com.chua.datasource.support.wrapper.EngineQueryWrapper;
import com.chua.datasource.support.wrapper.EngineUpdateWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
* 内存响应式引擎，真正响应式实现。
*
* <p>内存操作无阻塞 I/O，所有终端方法直接返回 {@link Flux}/{@link Mono}，
* 不经过 {@code boundedElastic} 调度，在订阅者线程直接执行。</p>
*
* @author CH
* @since 4.0.0.42
* @param clazz clazz
* @return 执行查询的结果
* @param pn pn
* @param ps ps
 */
@Spi("memory")
public class InMemoryReactorEngine implements ReactorEngine {
/**
* 查询。
* @param entityClass 实体类
* @return 查询的结果
 */

    private final InMemoryEngine delegate = new InMemoryEngine(); // delegate

    /**
    * 列表。
    * @return 列表的结果
     */
    @Override
    public <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new ReactorLambdaQueryWrapper<T>(delegate, entityClass) {
            @Override
            public Flux<T> list() {
                /**
                * one。
                * @return one的结果
                * @param clazz clazz
                * @param pn pn
                * @param ps ps
                 */
                return Flux.fromIterable(doQuery(entityClass));
            /**
            * one。
            * @return one的结果
            * @param clazz clazz
            * @param pn pn
            * @param ps ps
             */
            }

            @Override
            public Mono<T> one() {
                return Mono.fromSupplier(() -> {
                    List<T> r = doQuery(entityClass);
                    return r.isEmpty() ? null : r.getFirst();
                });
            }

            @Override
            public Mono<Page<T>> page(int pn, int ps) {
                return Mono.fromSupplier(() -> {
                    List<T> r = doQuery(entityClass);
                    int from = (pn - 1) * ps;
                    int to = Math.min(from + ps, r.size());
                    if (from >= r.size()) {
                        return new Page<T>(pn, ps, r.size(), List.of());
                    }
                    return new Page<T>(pn, ps, r.size(), r.subList(from, to));
                });
            }

            private <T> List<T> doQuery(Class<T> clazz) {
                EngineQueryWrapper<T> w = new EngineQueryWrapper<>(delegate, clazz);
                w.getConditions().addAll(getConditions());
                w.getOrderBys().addAll(getOrderBys());
                return delegate.evaluateQuery(w);
            }
        };
    }

    @Override
    public <T> ReactorLambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new ReactorLambdaUpdateWrapper<T>(delegate, entityClass) {
            @Override
            public Mono<Integer> update() {
                return Mono.fromSupplier(() -> {
                    EngineUpdateWrapper<T> w = new EngineUpdateWrapper<>(delegate, entityClass);
                    w.getConditions().addAll(getConditions());
                    w.getSetValues().putAll(getSetValues());
                    return delegate.evaluateUpdate(w);
                });
            }
        };
    }

    @Override
    public <T> ReactorLambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new ReactorLambdaDeleteWrapper<T>(delegate, entityClass) {
            @Override
            public Mono<Integer> remove() {
                return Mono.fromSupplier(() -> {
                    EngineDeleteWrapper<T> w = new EngineDeleteWrapper<>(delegate, entityClass);
                    w.getConditions().addAll(getConditions());
                    return delegate.evaluateDelete(w);
                });
            }
        };
    }

    @Override
    public Flux<Map<String, Object>> query(String sql, Object... params) {
        return Flux.defer(() -> Flux.fromIterable(delegate.querySql(sql, params)));
    }

    @Override
    public <T> Flux<T> query(String sql, Class<T> rowType, Object... params) {
        return Flux.defer(() -> {
            List<T> out = new java.util.ArrayList<>();
            for (Map<String, Object> row : delegate.querySql(sql, params)) {
                out.add(MemorySqlLex.RowAccessor.toBean(row, rowType));
            }
            return Flux.fromIterable(out);
        });
    }

    @Override
    public Mono<Integer> execute(String sql, Object... params) {
        return Mono.fromSupplier(() -> delegate.executeSql(sql, params));
    }

    @Override
    public Flux<Integer> batch(String sql, List<Object[]> batchParams) {
        return Flux.fromIterable(batchParams).map(bp -> delegate.executeSql(sql, bp));
    }

    /**
    * 存入表数据（委托同步引擎，供原生 SQL 与 Lambda 共享）。
    *
    * @param name 表名
    * @param data 行数据
    * @return 当前引擎
     */
    public InMemoryReactorEngine store(String name, java.util.List<?> data) {
        delegate.store(name, data);
        return this;
    }

    /**
    * 关闭引擎，释放内存数据。
     */
    public void close() {
        delegate.close();
    }
}