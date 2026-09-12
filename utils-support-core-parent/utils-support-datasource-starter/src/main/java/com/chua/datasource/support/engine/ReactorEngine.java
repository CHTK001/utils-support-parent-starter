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
* }</pre>>> rows = engine.query("select * from user where age > ?", 18);
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
    * 通过 SPI 创建响应式引擎实例。
    *
    * @param type SPI 扩展键（如 "MySQL"、"sqlite"、"duckdb"）
    * @return 响应式引擎实例
     */
    static ReactorEngine create(String type) {
        return com.chua.common.support.spi.ServiceProvider.of(ReactorEngine.class).getExtension(type);
    }
}