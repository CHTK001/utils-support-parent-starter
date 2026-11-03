package com.chua.datasource.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.annotations.Spi;
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
 */
@Spi("memory")
public class InMemoryReactorEngine implements ReactorEngine {

    private final InMemoryEngine delegate = new InMemoryEngine(); // delegate

    /**
     * 创建 Lambda 查询包装器。
     *
     * @param entityClass 实体类型
     * @param <T>         实体泛型
     * @return 查询包装器
     */
    @Override
    public <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass) {
        // 内存引擎没有 SqlExecutor，包装器默认的「渲染 SQL 再执行」路径会直接 NPE。
        // 统一由 MemoryReactorWrappers 走内存索引求值入口。
        return MemoryReactorWrappers.query(delegate, entityClass);
    }

    @Override
    public <T> ReactorLambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return MemoryReactorWrappers.update(delegate, entityClass);
    }

    @Override
    public <T> ReactorLambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return MemoryReactorWrappers.delete(delegate, entityClass);
    }

    @Override
    public Flux<Map<String, Object>> query(String sql, Object... params) {
        return Flux.defer(() -> Flux.fromIterable(delegate.querySql(sql, params)));
    }

    @Override
    public <T> Flux<T> query(String sql, Class<T> rowType, Object... params) {
        return Flux.defer(() -> {
            List<T> out = Wraps.list();
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
