package com.chua.common.support.lang.datasource.engine.executor;

import com.chua.common.support.lang.datasource.dialect.Pagination;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行器接口，负责执行 SQL 语句并返回结果。
 * <p>
 * 提供统一的 SQL 执行抽象，屏蔽底层 JDBC、连接池或其它数据源的差异。
 * 支持以下操作类型：
 * <ul>
 *   <li>查询 — {@link #query(String, Object...)} / {@link #query(String, Class, Object...)}</li>
 *   <li>分页查询 — {@link #queryPage(String, Pagination, Object...)}</li>
 *   <li>更新（INSERT/UPDATE/DELETE）— {@link #execute(String, Object...)}</li>
 *   <li>批量操作 — {@link #batch(String, List)}</li>
 * </ul>
 * </p>
 * <p>
 * 执行结果根据方法不同返回：
 * <ul>
 *   <li>查询返回 {@code List<Map<String, Object>>} 或通过类型映射返回 {@code List<T>}</li>
 *   <li>更新返回受影响的行数（int）</li>
 *   <li>批量返回每批受影响的行数（int[]）</li>
 * </ul>
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * SqlExecutor executor = engine.getExecutor();
 *
 * // 查询
 * List<Map<String, Object>> rows = executor.query("select * from user where age > ?", 18);
 *
 * // 类型映射查询
 * List<User> users = executor.query("select * from user", User.class);
 *
 * // 分页查询
 * Pagination page = new Pagination().setPageNum(1).setPageSize(10);
 * List<Map<String, Object>> pageResult = executor.queryPage("select * from user", page);
 *
 * // 更新
 * int affected = executor.execute("update user set name = ? where id = ?", "新名称", 1);
 *
 * // 批量
 * int[] results = executor.batch("insert into user(name) values(?)", List.of(new Object[]{"a"}, new Object[]{"b"}));
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public interface SqlExecutor {

    /**
     * 执行查询，返回 Map 行列表。
     * <p>每行数据以 {@code Map<String, Object>} 表示，key 为列名，value 为列值。</p>
     *
     * @param sql    SQL 语句（可使用 ? 占位符）
     * @param params 参数列表
     * @return 查询结果行列表
     */
    List<Map<String, Object>> query(String sql, Object... params);

    /**
     * 执行查询，自动将结果映射为指定类型的对象列表。
     * <p>通过约定或注解将列名映射到 Java 对象的属性名。</p>
     *
     * @param sql     SQL 语句
     * @param rowType 行类型
     * @param params  参数列表
     * @param <T>     行类型参数
     * @return 类型化结果列表
     */
    <T> List<T> query(String sql, Class<T> rowType, Object... params);

    /**
     * 执行分页查询。
     * <p>自动根据 {@link Pagination} 中的 offset 和 limit 对 SQL 进行分页包装。</p>
     *
     * @param sql        原始 SQL（不含分页）
     * @param pagination 分页参数
     * @param params     参数列表
     * @return 当前页的结果行列表
     */
    List<Map<String, Object>> queryPage(String sql, Pagination pagination, Object... params);

    /**
     * 执行更新操作（INSERT / UPDATE / DELETE）。
     *
     * @param sql    SQL 语句
     * @param params 参数列表
     * @return 受影响的行数
     */
    int execute(String sql, Object... params);

    /**
     * 执行批量操作。
     * <p>适用于批量 INSERT 或 UPDATE，所有批次使用相同的 SQL 模板。</p>
     *
     * @param sql          SQL 模板
     * @param batchParams  批量参数列表，每个元素是一组参数
     * @return 每批操作影响的行数数组
     */
    int[] batch(String sql, List<Object[]> batchParams);
}
