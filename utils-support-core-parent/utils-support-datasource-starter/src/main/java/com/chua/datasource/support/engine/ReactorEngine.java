package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 响应式引擎接口，方法名与 {@link Engine} 一致，Lambda 链式 API 也一致，
 * 仅返回类型为 Reactor 响应式类型（{@link Flux} / {@link Mono}）。
 *
 * <p>底层通过 {@code boundedElastic} 调度器执行阻塞的 JDBC 调用，
 * 避免阻塞 Reactor 事件循环线程。适用于 Spring WebFlux、Spring Cloud Gateway
 * 等响应式框架。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * ReactorEngine engine = new DefaultReactorEngine(jdbcEngine);
 *
 * // Lambda 链式查询（返回 Flux）
 * Flux<User> users = engine.query(User.class)
 *     .eq(User::getName, "张三")
 *     .gt(User::getAge, 18)
 *     .list();
 *
 * // Lambda 链式更新（返回 Mono）
 * Mono<Integer> affected = engine.update(User.class)
 *     .set(User::getName, "李四")
 *     .eq(User::getId, 1)
 *     .update();
 *
 * // 原生 SQL 查询（返回 Flux）
 * Flux<Map<String, Object>> rows = engine.query("select * from user where age > ?", 18);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ReactorEngine {

    /**
     * 创建响应式 Lambda 查询包装器。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 响应式查询包装器
     */
    <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass);

    /**
     * 创建响应式 Lambda 更新包装器。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 响应式更新包装器
     */
    <T> ReactorLambdaUpdateWrapper<T> update(Class<T> entityClass);

    /**
     * 创建响应式 Lambda 删除包装器。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 响应式删除包装器
     */
    <T> ReactorLambdaDeleteWrapper<T> delete(Class<T> entityClass);

    /**
     * 响应式原生 SQL 查询，返回 映射 行列表的 Flux。
     *
     * @param sql    SQL 语句
     * @param params 参数列表
     * @return 查询结果行 Flux
     */
    Flux<Map<String, Object>> query(String sql, Object... params);

    /**
     * 响应式原生 SQL 查询，自动映射为指定类型对象列表的 Flux。
     *
     * @param sql     SQL 语句
     * @param rowType 行类型
     * @param params  参数列表
     * @param <T>     行类型
     * @return 类型化结果 Flux
     */
    <T> Flux<T> query(String sql, Class<T> rowType, Object... params);

    /**
     * 响应式更新（插入 / 更新 / 删除）。
     *
     * @param sql    SQL 语句
     * @param params 参数列表
     * @return 受影响行数 Mono
     */
    Mono<Integer> execute(String sql, Object... params);

    /**
     * 响应式批量操作。
     *
     * @param sql         SQL 模板
     * @param batchParams 批量参数列表
     * @return 每批影响行数 Flux
     */
    Flux<Integer> batch(String sql, List<Object[]> batchParams);

    /**
     * 查询单行并映射为指定类型（便捷方法）。
     *
     * <p>基于 {@link #query(String, Class, Object...)} 实现，命中多行时返回第一行，
     * 无命中时返回 {@link Mono#empty()}。本方法不自行追加 {@code LIMIT 1}，
     * 截断方式由底层引擎决定；需要严格单行语义时请在 ql 中自行限定，
     * 语义与同步 {@link Engine#queryOne(String, Class, Object...)} 对齐。</p>
     *
     * @param sql     SQL 语句
     * @param rowType 行类型
     * @param params  参数列表
     * @param <T>     行类型
     * @return 映射后的对象，无命中返回 {@link Mono#empty()}
     */
    default <T> Mono<T> queryOne(String sql, Class<T> rowType, Object... params) {
        return query(sql, rowType, params).next();
    }

    /**
     * 查询单行单列的原始值（便捷方法）。
     *
     * <p>基于 {@link #query(String, Object...)} 实现，取结果集第一行第一列；
     * 适用于 {@code SELECT count(*)}、{@code SELECT max(id)} 这类标量查询。
     * 列标签的映射规则由底层引擎决定，调用方不应依赖具体标签名，
     * 语义与同步 {@link Engine#queryValue(String, Object...)} 对齐。</p>
     *
     * @param sql    SQL 语句
     * @param params 参数列表
     * @return 首行首列值，无结果或首行为空时返回 {@link Mono#empty()}
     */
    default Mono<Object> queryValue(String sql, Object... params) {
        return query(sql, params).next().handle((first, sink) -> {
            if (first == null || first.isEmpty()) {
                sink.complete();
                return;
            }
            sink.next(first.values().iterator().next());
        });
    }

    /**
     * 统计原生查询结果行数（便捷方法）。
     *
     * <p>要求 ql 返回单行单列的数值（如 {@code SELECT count(*) FROM t WHERE ...}）。
     * 数值转换规则与同步 {@link Engine#count(String, Object...)} 一致：
     * {@link Number} 直接取 long，否则按字符串解析，无法解析时抛
     * {@link NumberFormatException}；无结果时返回 0。</p>
     *
     * @param sql    计数查询语句
     * @param params 参数列表
     * @return 首行首列数值转 long，无结果时返回 0
     * @throws NumberFormatException 首列不是数值时抛出
     */
    default Mono<Long> count(String sql, Object... params) {
        return queryValue(sql, params).defaultIfEmpty(0L).map(v -> {
            if (v instanceof Number number) {
                return number.longValue();
            }
            String text = String.valueOf(v).trim();
            return text.isEmpty() ? 0L : Long.parseLong(text);
        });
    }

    /**
     * 判断原生查询是否至少命中一行（便捷方法）。
     *
     * <p>基于 {@link #query(String, Object...)} 实现，语义与同步
     * {@link Engine#exists(String, Object...)} 对齐。</p>
     *
     * @param sql    SQL 语句
     * @param params 参数列表
     * @return 命中至少一行返回 true
     */
    default Mono<Boolean> exists(String sql, Object... params) {
        return query(sql, params).hasElements();
    }

    /**
     * 分页执行原生查询（便捷方法）。
     *
     * <p>基于 {@link #query(String, Object...)} 全量查询后内存截取，适用于
     * 数据量可控的场景；数据量大时建议改用底层执行器的物理分页
     * （由方言渲染 LIMIT / OFFSET），与同步 {@link Engine#queryPage} 的
     * 回退路径语义一致。</p>
     *
     * @param sql      查询语句
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param params   参数列表
     * @return 当前页数据行 Flux
     * @throws IllegalArgumentException 页码或每页条数不大于 0 时抛出
     */
    default Flux<Map<String, Object>> queryPage(String sql, int pageNum, int pageSize, Object... params) {
        if (pageNum < 1) {
            return Flux.error(new IllegalArgumentException("页码必须大于 0"));
        }
        if (pageSize < 1) {
            return Flux.error(new IllegalArgumentException("每页条数必须大于 0"));
        }
        long skip = (long) (pageNum - 1) * pageSize;
        return query(sql, params).skip(skip).take(pageSize);
    }

    /**
     * 通过 SPI 创建响应式引擎实例。
     *
     * @param type SPI 扩展键（如 "MySQL"、"sqlite"、"duckdb"）
     * @return 响应式引擎实例
     */
    static ReactorEngine create(String type) {
        return com.chua.common.support.spi.ServiceProvider.of(ReactorEngine.class).getExtension(type);
    }
}
