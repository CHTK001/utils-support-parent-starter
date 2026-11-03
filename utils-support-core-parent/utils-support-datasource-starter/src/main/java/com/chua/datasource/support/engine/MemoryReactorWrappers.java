package com.chua.datasource.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.wrapper.EngineDeleteWrapper;
import com.chua.datasource.support.wrapper.EngineQueryWrapper;
import com.chua.datasource.support.wrapper.EngineUpdateWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 为「没有 {@code SqlExecutor} 的引擎」构造响应式 Lambda 包装器。
 *
 * <p>响应式包装器 {@link ReactorLambdaQueryWrapper} / {@link ReactorLambdaUpdateWrapper} /
 * {@link ReactorLambdaDeleteWrapper} 的默认终端方法会把条件渲染成 SQL 再交给
 * {@code engine.getExecutor()} 执行。这依赖 JDBC / R2DBC 执行器，而内存引擎
 * （{@link InMemoryEngine}）、文件引擎（{@link FileEngine}）以及只实现了内存求值的
 * 自定义引擎都没有执行器——直接用默认实现会抛
 * {@code NullPointerException: ... because the return value of "Engine.getExecutor()" is null}，
 * 而这一步发生在调用方的业务代码里，极难定位。</p>
 *
 * <p>本类是这类引擎唯一的适配入口，语义与同步引擎完全一致：</p>
 * <ul>
 *   <li>查询 —— 条件/排序/limit/offset 平移后交给 {@link AbstractEngine#executeQuery}；
 *       内存引擎额外复用索引求值（{@link InMemoryEngine#evaluateQuery}），避免退化成全表扫描</li>
 *   <li>分页 —— 先在完整命中集上求值再按页码截取，绝不让 wrapper 自身的 limit 参与，
 *       否则第二页起恒为空</li>
 *   <li>计数 —— 条件命中的总行数（不含分页），与 {@link AbstractEngine#queryCount} 一致</li>
 *   <li>更新/删除 —— 复用引擎自身的求值入口，从而保留「禁止无条件全表操作」守卫
 *       与文件引擎的写回逻辑</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class MemoryReactorWrappers {

    /**
     * 工具类，禁止实例化。
     */
    private MemoryReactorWrappers() {
    }

    /**
     * 构造查询包装器。
     *
     * @param engine      底层引擎
     * @param entityClass 实体类型
     * @param <T>         实体泛型
     * @return 查询包装器
     */
    static <T> ReactorLambdaQueryWrapper<T> query(AbstractEngine engine, Class<T> entityClass) {
        return new ReactorLambdaQueryWrapper<T>(engine, entityClass) {
            @Override
            public Flux<T> list() {
                return Flux.fromIterable(doQuery(entityClass));
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
                if (ps < 1) {
                    return Mono.error(new IllegalArgumentException("每页条数必须大于 0"));
                }
                int pageNum = Math.max(pn, 1);
                return Mono.fromSupplier(() -> {
                    // 分页基于完整命中集：wrapper 自身的 limit 若参与其中，第二页起会恒为空。
                    List<T> r = doQuery(entityClass, false);
                    int from = (pageNum - 1) * ps;
                    if (from >= r.size()) {
                        return new Page<T>(pageNum, ps, r.size(), List.of());
                    }
                    int to = Math.min(from + ps, r.size());
                    // 独立副本，避免 subList 视图持有内存存储引用
                    return new Page<T>(pageNum, ps, r.size(), Wraps.list(r.subList(from, to)));
                });
            }

            @Override
            public Mono<Long> count() {
                // 语义与 AbstractEngine.queryCount 一致：条件命中的总行数（不含分页）。
                return Mono.fromSupplier(() -> (long) doQuery(entityClass, false).size());
            }

            private <T> List<T> doQuery(Class<T> clazz) {
                return doQuery(clazz, true);
            }

            @SuppressWarnings({"unchecked", "rawtypes"})
            private <T> List<T> doQuery(Class<T> clazz, boolean applyPaging) {
                EngineQueryWrapper<T> w = new EngineQueryWrapper<>(engine, clazz);
                w.getConditions().addAll((List) getConditions());
                w.getOrderBys().addAll(getOrderBys());
                int limit = buildSql().limit();
                int offset = buildSql().offset();
                if (engine instanceof InMemoryEngine memory) {
                    // 内存索引求值不看 wrapper 的 limit/offset，必须在这里显式截取，
                    // 否则 .limit(n) 静默失效、调用方拿到比请求更多的行。
                    List<T> rows = (List<T>) memory.evaluateQuery(w);
                    return applyPaging ? slice(rows, limit, offset) : rows;
                }
                // 其他引擎（文件等）的 executeQuery 会按 limit/offset 截取
                if (applyPaging) {
                    if (limit > 0) {
                        w.limit(limit);
                    }
                    if (offset > 0) {
                        w.offset(offset);
                    }
                }
                return (List<T>) engine.executeQuery(w, clazz);
            }

            /**
             * 按 limit/offset 截取结果（0 表示不限制 / 不偏移）。
             *
             * @param rows   完整命中集
             * @param limit  行数上限
             * @param offset 偏移行数
             * @param <T>    实体类型
             * @return 截取后的独立副本
             */
            private <T> List<T> slice(List<T> rows, int limit, int offset) {
                if (limit <= 0 && offset <= 0) {
                    return rows;
                }
                int from = Math.min(offset, rows.size());
                if (from >= rows.size()) {
                    return Wraps.list();
                }
                int to = limit > 0 ? Math.min(from + limit, rows.size()) : rows.size();
                return Wraps.list(rows.subList(from, to));
            }
        };
    }

    /**
     * 构造更新包装器。
     *
     * @param engine      底层引擎
     * @param entityClass 实体类型
     * @param <T>         实体泛型
     * @return 更新包装器
     */
    static <T> ReactorLambdaUpdateWrapper<T> update(AbstractEngine engine, Class<T> entityClass) {
        return new ReactorLambdaUpdateWrapper<T>(engine, entityClass) {
            @Override
            public Mono<Integer> update() {
                return Mono.fromSupplier(() -> {
                    EngineUpdateWrapper<T> w = new EngineUpdateWrapper<>(engine, entityClass);
                    w.getConditions().addAll(getConditions());
                    w.getSetValues().putAll(getSetValues());
                    if (engine instanceof InMemoryEngine memory) {
                        return memory.evaluateUpdate(w);
                    }
                    UpdateSql<T> sql = w.buildSql();
                    return engine.executeUpdate(sql);
                });
            }
        };
    }

    /**
     * 构造删除包装器。
     *
     * @param engine      底层引擎
     * @param entityClass 实体类型
     * @param <T>         实体泛型
     * @return 删除包装器
     */
    static <T> ReactorLambdaDeleteWrapper<T> delete(AbstractEngine engine, Class<T> entityClass) {
        return new ReactorLambdaDeleteWrapper<T>(engine, entityClass) {
            @Override
            public Mono<Integer> remove() {
                return Mono.fromSupplier(() -> {
                    EngineDeleteWrapper<T> w = new EngineDeleteWrapper<>(engine, entityClass);
                    w.getConditions().addAll(getConditions());
                    if (engine instanceof InMemoryEngine memory) {
                        return memory.evaluateDelete(w);
                    }
                    DeleteSql<T> sql = w.buildSql();
                    return engine.executeDelete(sql);
                });
            }
        };
    }
}
