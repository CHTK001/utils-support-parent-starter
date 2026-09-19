package com.chua.common.support.lang.datasource.flyway;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 迁移版本记录表读写工具，是所有 {@link Flyway} 实现的唯一历史表入口。
 *
 * <p>历史上 {@code DefaultFlyway}（{@code flyway_schema_history}，{@code version BIGINT}）与
 * {@code DataSourceFlyway}（{@code sys_database_version}，点分版本字符串）各自建表，
 * 同一套脚本被两个实现识别为不同历史，甚至同名不同列时相互冲突。本类把表名、列名、
 * 建表/读写语句收敛到一处，实现之间只保留"如何执行 SQL"的差异（{@link Runner}）。</p>
 *
 * <h2>记录语义</h2>
 * <ul>
 *   <li>主键为复合键 {@code (version, script_name)}：同一版本含多个脚本
 *       （{@code V1.0__init_monitor} 与 {@code V1.0__init_server}）时逐条记录互不覆盖</li>
 *   <li>{@code success} 取值 {@value #SUCCESS_TRUE} / {@value #SUCCESS_FALSE}，
 *       失败脚本在后续启动可被单独重试，已成功脚本跳过</li>
 *   <li>{@code checksum} 为脚本内容 MD5（见 {@link FlywayScripts#checksum(String)}），
 *       失败记录写入占位值 {@value #CHECKSUM_FAILED}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FlywayHistory {

    /**
     * 版本记录表名（与内置 Spring 侧记录表默认值一致，避免记录表分裂）
     */
    public static final String HISTORY_TABLE = "sys_database_version";

    /**
     * 列名：版本
     */
    public static final String COL_VERSION = "version";

    /**
     * 列名：描述
     */
    public static final String COL_DESCRIPTION = "description";

    /**
     * 列名：脚本文件名
     */
    public static final String COL_SCRIPT_NAME = "script_name";

    /**
     * 列名：内容校验和
     */
    public static final String COL_CHECKSUM = "checksum";

    /**
     * 列名：成功标志
     */
    public static final String COL_SUCCESS = "success";

    /**
     * 成功标志取值
     */
    public static final String SUCCESS_TRUE = "true";

    /**
     * 失败标志取值
     */
    public static final String SUCCESS_FALSE = "false";

    /**
     * 失败记录的校验和占位值
     */
    public static final String CHECKSUM_FAILED = "FAILED";

    /**
     * 建表语句
     */
    private static final String CREATE_SQL = "CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
            + COL_VERSION + " VARCHAR(64) NOT NULL, "
            + COL_DESCRIPTION + " VARCHAR(255), "
            + COL_SCRIPT_NAME + " VARCHAR(255) NOT NULL, "
            + COL_CHECKSUM + " VARCHAR(64), "
            + COL_SUCCESS + " VARCHAR(8), "
            + "CONSTRAINT pk_" + HISTORY_TABLE + " PRIMARY KEY (" + COL_VERSION + ", " + COL_SCRIPT_NAME + "))";

    /**
     * 查询已应用脚本
     */
    private static final String SELECT_SQL = "SELECT " + COL_SCRIPT_NAME + ", " + COL_SUCCESS
            + " FROM " + HISTORY_TABLE;

    /**
     * 插入执行记录
     */
    private static final String INSERT_SQL = "INSERT INTO " + HISTORY_TABLE
            + " (" + COL_VERSION + ", " + COL_DESCRIPTION + ", " + COL_SCRIPT_NAME
            + ", " + COL_CHECKSUM + ", " + COL_SUCCESS + ") VALUES (?, ?, ?, ?, ?)";

    /**
     * 按复合主键更新执行记录
     */
    private static final String UPDATE_SQL = "UPDATE " + HISTORY_TABLE
            + " SET " + COL_CHECKSUM + " = ?, " + COL_SUCCESS + " = ? WHERE "
            + COL_VERSION + " = ? AND " + COL_SCRIPT_NAME + " = ?";

    private FlywayHistory() {
    }

    /**
     * 历史表读写所需的最小 SQL 执行能力。
     *
     * <p>由各 {@link Flyway} 实现按自身底座适配：{@code Engine} 底座直接转发
     * {@code execute/query}，JDBC 底座使用 {@code PreparedStatement}。</p>
     */
    public interface Runner {

        /**
         * 执行更新类语句。
         *
         * @param sql    语句
         * @param params 占位参数
         * @return 受影响行数
         */
        int update(String sql, Object... params);

        /**
         * 执行查询类语句。
         *
         * @param sql    语句
         * @param params 占位参数
         * @return 结果行列表
         */
        List<Map<String, Object>> select(String sql, Object... params);
    }

    /**
     * 确保版本记录表存在。
     *
     * @param runner SQL 执行器
     */
    public static void ensure(Runner runner) {
        runner.update(CREATE_SQL);
    }

    /**
     * 读取已应用脚本的执行状态。
     *
     * @param runner SQL 执行器
     * @return 脚本文件名 → {@code success} 标志映射，无记录时为空映射
     */
    public static Map<String, String> loadApplied(Runner runner) {
        Map<String, String> applied = new HashMap<>();
        for (Map<String, Object> row : runner.select(SELECT_SQL)) {
            Object name = row.get(COL_SCRIPT_NAME);
            if (name == null) {
                name = row.get(COL_SCRIPT_NAME.toUpperCase());
            }
            if (name != null) {
                Object success = row.get(COL_SUCCESS);
                if (success == null) {
                    success = row.get(COL_SUCCESS.toUpperCase());
                }
                applied.put(String.valueOf(name), success == null ? null : String.valueOf(success));
            }
        }
        return applied;
    }

    /**
     * 写入一次脚本执行结果。
     *
     * <p>先按复合主键 UPDATE；无记录时 INSERT。并发启动下 INSERT 可能撞主键，
     * 此时降级为 UPDATE；若降级更新仍无行受影响（表不存在、约束异常等真实故障），
     * 原始异常继续抛出，不静默丢失迁移记录。</p>
     *
     * @param runner      SQL 执行器
     * @param version     版本字符串
     * @param description 脚本描述
     * @param scriptName  脚本文件名
     * @param checksum    内容校验和
     * @param success     成功标志
     */
    public static void record(Runner runner, String version, String description, String scriptName,
                              String checksum, String success) {
        if (runner.update(UPDATE_SQL, checksum, success, version, scriptName) > 0) {
            return;
        }
        try {
            runner.update(INSERT_SQL, version, description, scriptName, checksum, success);
        } catch (RuntimeException e) {
            if (runner.update(UPDATE_SQL, checksum, success, version, scriptName) == 0) {
                throw new IllegalStateException("记录迁移版本失败: " + scriptName + ": " + e.getMessage(), e);
            }
        }
    }
}
