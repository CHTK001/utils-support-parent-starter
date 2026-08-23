package com.chua.common.support.lang.datasource.flyway;

import java.nio.file.Path;

/**
 * 数据库迁移接口，提供类似 Flyway 的 SQL 脚本版本化管理能力。
 *
 * <p>规范命名的迁移脚本（{@code V{版本}__{描述}.sql}）按版本升序执行，
 * 已应用的迁移记录在内存中，重复 {@link #migrate()} 不会重复执行。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * Engine engine = DuckDBEngine.create("duckdb");
 * engine.flyway()
 *     .location("classpath:db/migration")
 *     .migrate();
 * }</pre>
 *
 * <h2>迁移脚本命名</h2>
 * <ul>
 *   <li>{@code V1__create_user.sql}</li>
 *   <li>{@code V2__add_index.sql}</li>
 *   <li>{@code V3__update_orders.sql}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Flyway {

    /**
     * 添加迁移脚本位置。
     * <p>支持文件系统路径（{@code /path/to/migrations}）与 classpath 前缀（{@code classpath:db/migration}）。</p>
     *
     * @param location 脚本位置
     * @return this
     */
    Flyway location(String location);

    /**
     * 设置版本与描述的分隔符，默认 {@code __}。
     *
     * @param separator 分隔符
     * @return this
     */
    Flyway separator(String separator);

    /**
     * 获取迁移信息，包含已应用与待应用的脚本。
     *
     * @return 迁移信息列表（按版本升序）
     */
    java.util.List<MigrationInfo> info();

    /**
     * 执行所有未应用的迁移脚本。
     *
     * @return 本次执行的脚本数量
     */
    int migrate();

    /**
     * 执行单个 SQL 脚本文件，不纳入版本记录。
     *
     * @param script 脚本文件
     * @return 执行语句数量
     */
    int execute(Path script);
}