package com.chua.datasource.support.wrapper;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.wrapper.AbstractLambdaWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * 响应式 Lambda 删除包装器，条件 API 与 {@code LambdaDeleteWrapper} 一致，
 * 终端方法 {@link #remove()} 返回 {@link Mono}。
 *
 * <pre>{@code
 * Mono<Integer> affected = engine.delete(User.class)
 *     .eq(User::getId, 1)
 *     .remove();
 * }</pre>    .remove();
 * }</pre>
 *
 * @param <T> 实体类型
 * @author CH
 * @since 4.0.0.42
 */
public class ReactorLambdaDeleteWrapper<T> extends AbstractLambdaWrapper<T, ReactorLambdaDeleteWrapper<T>> {

    /**
     * 底层同步引擎
     */
    private final Engine engine;

    /**
     * 创建响应式删除包装器。
     *
     * @param engine      底层引擎
     * @param entityClass 实体类
     */
    public ReactorLambdaDeleteWrapper(Engine engine, Class<T> entityClass) {
        super(entityClass);
        this.engine = engine;
    }

    /**
     * 构建删除 SQL 信息。
     * @return 构建sql的结果
     */
    public DeleteSql<T> buildSql() {
        if (conditions.isEmpty() && !fullTableAllowed) {
            throw new IllegalStateException("禁止无 WHERE 条件的全表删除: "
                    + entityClass.getSimpleName() + "，如需全表删除请显式调用 allowFullTable()");
        }
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        buildWhere(where, params);
        return new DeleteSql<>(entityClass, where.toString(), params);
    }

    /**
     * 执行删除操作，返回受影响行数的 Mono。
     *
     * @return 受影响行数 Mono
     */
    public Mono<Integer> remove() {
        return Mono.fromCallable(this::doRemove)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 同步执行删除（内部使用）。
     * @return 执行移除的结果
     */
    private int doRemove() {
        DeleteSql<T> sql = buildSql();
        String tableName = com.chua.datasource.support.engine.AbstractEngine.resolveTableName(entityClass);
        StringBuilder fullSql = new StringBuilder("DELETE FROM ").append(tableName);
        if (sql.hasWhere()) {
            fullSql.append(" WHERE ").append(sql.whereClause());
        }
        return engine.getExecutor().execute(fullSql.toString(), sql.params().toArray());
    }

    @Override
    protected ReactorLambdaDeleteWrapper<T> newInstance() {
        return new ReactorLambdaDeleteWrapper<>(engine, entityClass);
    }

    @Override
    protected String resolveColumn(SFunction<T, ?> column) {
        return LambdaUtils.resolveColumn(column);
    }
}
