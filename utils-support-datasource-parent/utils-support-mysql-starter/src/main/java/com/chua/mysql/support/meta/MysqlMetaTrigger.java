package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.TriggerCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.TriggerDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaTrigger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * MySQL 触发器元数据操作。
 * <p>
 * 读取路径为 {@code INFORMATION_SCHEMA.TRIGGERS}：
 * {@code TRIGGER_SCHEMA = COALESCE(?, DATABASE())}，已调用 {@code onTable(String)} 时追加
 * {@code EVENT_OBJECT_TABLE = ?}，按触发器名查询时追加 {@code TRIGGER_NAME = ?}，三者都是绑定参数。
 * 不再走 {@code DatabaseMetaData#getTables(..., "TRIGGER")}（该结果集根本没有 {@code TRIGGER_NAME} 列，
 * 只会得到空列表），也不再使用 {@code SHOW CREATE TRIGGER} 与"失败回退拼字符串查询"的写法。
 * </p>
 * <p>
 * 能力边界：
 * <ul>
 *   <li>MySQL 触发器恒为 {@code FOR EACH ROW}，{@code forEachStatement()} 显式拒绝，不静默降级。</li>
 *   <li>MySQL 没有 {@code ALTER TABLE ... ENABLE/DISABLE TRIGGER} 语法（那是 PostgreSQL 的），
 *       {@code enable(String)} / {@code disable(String)} 与构建器的 {@code disable()} 显式抛
 *       {@link UnsupportedOperationException}。</li>
 *   <li>MySQL 触发器无注释子句，{@code comment(String)} 显式拒绝。</li>
 *   <li>因此 {@link TriggerDef#getStatus()} 在 MySQL 侧恒为 {@code null}（既无禁用态也就没有状态可报），不伪造
 *       {@code ENABLED}。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlMetaTrigger extends AbstractMetaTrigger {

    /**
     * 触发器查询：库名绑定，表名/触发器名按需追加绑定条件。
     */
    private static final String TRIGGER_SQL =
            "SELECT t.TRIGGER_CATALOG, t.TRIGGER_SCHEMA, t.TRIGGER_NAME, t.EVENT_OBJECT_TABLE, t.ACTION_TIMING,"
                    + " t.EVENT_MANIPULATION, t.ACTION_STATEMENT, t.ACTION_REFERENCE_NEW_ROW"
                    + " FROM INFORMATION_SCHEMA.TRIGGERS t"
                    + " WHERE t.TRIGGER_SCHEMA = COALESCE(?, DATABASE())";

    /**
     * 构造方法（无触发器名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected MysqlMetaTrigger(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带触发器名上下文）。
     *
     * @param metaData    元数据入口
     * @param engine      引擎实例
     * @param triggerName 触发器名
     */
    protected MysqlMetaTrigger(AbstractMetaData metaData, Engine engine, String triggerName) {
        super(metaData, engine, triggerName);
    }

    /**
     * 列出触发器：已调用 {@code onTable(String)} 时只返回该表的触发器。
     *
     * @return 触发器定义列表
     * @throws IllegalStateException 查询失败
     */
    @Override
    public List<TriggerDef> list() {
        return readTriggers(null);
    }

    /**
     * 获取指定触发器定义。
     *
     * @param triggerName 触发器名，为 {@code null} 时使用构造期上下文
     * @return 触发器定义，不存在时返回 {@code null}
     * @throws IllegalStateException 未指定触发器名或查询失败
     */
    @Override
    public TriggerDef get(String triggerName) {
        String target = triggerName != null ? triggerName : this.triggerName;
        if (target == null) {
            throw new IllegalStateException("未指定触发器名");
        }
        List<TriggerDef> defs = readTriggers(target);
        return defs.isEmpty() ? null : defs.get(0);
    }

    @Override
    public TriggerCreateBuilder create(String triggerName) {
        return new MysqlTriggerCreateBuilder(this, triggerName);
    }

    /**
     * 删除触发器。
     *
     * @param triggerName 触发器名
     * @return 是否成功
     * @throws IllegalStateException 未指定触发器名或执行失败
     */
    @Override
    public boolean drop(String triggerName) {
        if (triggerName == null) {
            throw new IllegalStateException("未指定触发器名");
        }
        return MysqlMetaData.execute(engine, "删除触发器 " + triggerName,
                "DROP TRIGGER IF EXISTS " + qualified(triggerName), List.of());
    }

    @Override
    public boolean enable(String triggerName) {
        throw new UnsupportedOperationException("MySQL 不支持启用/禁用触发器（无 ALTER TRIGGER 语法）");
    }

    @Override
    public boolean disable(String triggerName) {
        throw new UnsupportedOperationException("MySQL 不支持启用/禁用触发器（无 ALTER TRIGGER 语法）");
    }

    /**
     * 按条件读取触发器定义。
     *
     * @param name 触发器名过滤，{@code null} 表示不过滤
     * @return 触发器定义列表
     * @throws IllegalStateException 查询失败
     */
    private List<TriggerDef> readTriggers(String name) {
        StringBuilder sql = new StringBuilder(TRIGGER_SQL);
        List<Object> args = MysqlMetaData.args(MysqlMetaData.resolveSchema(metaData));
        if (tableName != null) {
            sql.append(" AND t.EVENT_OBJECT_TABLE = ?");
            args.add(tableName);
        }
        if (name != null) {
            sql.append(" AND t.TRIGGER_NAME = ?");
            args.add(name);
        }
        sql.append(" ORDER BY t.TRIGGER_NAME");
        return MysqlMetaData.query(engine, "查询触发器" + (name == null ? "" : " " + name),
                sql.toString(), args, MysqlMetaTrigger::mapTrigger);
    }

    /**
     * 触发器结果集映射，逐列对应 {@link TriggerDef} 属性。
     *
     * @param rs 结果集当前行
     * @return 触发器定义
     * @throws SQLException 读取失败
     */
    private static TriggerDef mapTrigger(ResultSet rs) throws SQLException {
        TriggerDef def = new TriggerDef();
        def.setName(rs.getString("TRIGGER_NAME"));
        def.setCatalog(MysqlMetaData.trimToNull(rs.getString("TRIGGER_CATALOG")));
        def.setSchema(rs.getString("TRIGGER_SCHEMA"));
        def.setTableName(rs.getString("EVENT_OBJECT_TABLE"));
        def.setTiming(MysqlMetaData.trimToNull(rs.getString("ACTION_TIMING")));
        def.setEvent(MysqlMetaData.trimToNull(rs.getString("EVENT_MANIPULATION")));
        def.setBody(rs.getString("ACTION_STATEMENT"));
        // MySQL 用 ACTION_REFERENCE_NEW_ROW='ROW' 表达逐行触发，语句级触发为空串
        def.setForEachRow("ROW".equalsIgnoreCase(MysqlMetaData.trimToNull(rs.getString("ACTION_REFERENCE_NEW_ROW"))));
        // MySQL 无触发器启用/禁用状态，保持 null
        return def;
    }

    /**
     * 生成带库名前缀的触发器引用名；库名未指定时交给会话默认库。
     *
     * @param name 触发器名
     * @return 引用后的触发器名
     */
    private String qualified(String name) {
        String schema = MysqlMetaData.resolveSchema(metaData);
        return schema == null ? MysqlMetaData.quote(name)
                : MysqlMetaData.quote(schema) + "." + MysqlMetaData.quote(name);
    }

    /**
     * 校验并归一触发事件。
     *
     * @param event 事件
     * @return 大写事件名
     */
    private static String checkEvent(String event) {
        String upper = event == null ? "" : event.trim().toUpperCase();
        if (!"INSERT".equals(upper) && !"UPDATE".equals(upper) && !"DELETE".equals(upper)) {
            throw new IllegalArgumentException("MySQL 触发事件仅支持 INSERT / UPDATE / DELETE，实际为: " + event);
        }
        return upper;
    }

    /**
     * MySQL 建触发器链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlTriggerCreateBuilder implements TriggerCreateBuilder {

        /**
         * 所属触发器元数据入口
         */
        private final MysqlMetaTrigger metaTrigger;
        /**
         * 触发器名
         */
        private final String triggerName;
        /**
         * 所属表名
         */
        private String tableName;
        /**
         * 触发时机
         */
        private String timing;
        /**
         * 触发事件
         */
        private String event;
        /**
         * 触发器体
         */
        private String body;

        MysqlTriggerCreateBuilder(MysqlMetaTrigger metaTrigger, String triggerName) {
            this.metaTrigger = metaTrigger;
            this.triggerName = triggerName;
        }

        @Override
        public TriggerCreateBuilder onTable(String tableName) {
            this.tableName = tableName;
            return this;
        }

        @Override
        public TriggerCreateBuilder before(String event) {
            this.timing = "BEFORE";
            this.event = checkEvent(event);
            return this;
        }

        @Override
        public TriggerCreateBuilder after(String event) {
            this.timing = "AFTER";
            this.event = checkEvent(event);
            return this;
        }

        @Override
        public TriggerCreateBuilder insteadOf(String event) {
            throw new UnsupportedOperationException("MySQL 不支持 INSTEAD OF 触发器");
        }

        @Override
        public TriggerCreateBuilder forEachRow() {
            return this;
        }

        @Override
        public TriggerCreateBuilder forEachStatement() {
            throw new UnsupportedOperationException("MySQL 触发器只支持 FOR EACH ROW");
        }

        @Override
        public TriggerCreateBuilder body(String body) {
            this.body = body;
            return this;
        }

        @Override
        public TriggerCreateBuilder enable() {
            return this;
        }

        @Override
        public TriggerCreateBuilder disable() {
            throw new UnsupportedOperationException("MySQL 不支持创建禁用态触发器");
        }

        @Override
        public TriggerCreateBuilder comment(String comment) {
            throw new UnsupportedOperationException("MySQL 触发器没有注释子句");
        }

        /**
         * 执行建触发器语句，并以字典读回结果作为返回值。
         * <p>触发器体含多条语句时自动包一层 {@code BEGIN ... END}。</p>
         *
         * @return 落库后的触发器定义
         * @throws IllegalStateException 参数不完整或执行失败
         */
        @Override
        public TriggerDef execute() {
            if (tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
            }
            if (timing == null || event == null) {
                throw new IllegalStateException("未指定触发时机和事件，请先调用 before(String) 或 after(String)");
            }
            if (body == null || body.trim().isEmpty()) {
                throw new IllegalStateException("触发器体不能为空");
            }
            String trimmed = body.trim();
            String statement = trimmed.indexOf(';') >= 0
                    ? "BEGIN\n  " + trimmed.replace("\n", "\n  ") + "\nEND"
                    : trimmed;
            String sql = "CREATE TRIGGER " + metaTrigger.qualified(triggerName) + " " + timing + " " + event
                    + " ON " + MysqlMetaData.quote(tableName) + "\nFOR EACH ROW\n" + statement;
            MysqlMetaData.execute(metaTrigger.engine, "创建触发器 " + triggerName, sql, List.of());
            return new MysqlMetaTrigger(metaTrigger.metaData, metaTrigger.engine)
                    .readTriggerOn(tableName, triggerName);
        }
    }

    /**
     * 在指定表上下文中读取单个触发器，供构建器回填返回值。
     *
     * @param table       表名
     * @param triggerName 触发器名
     * @return 触发器定义，不存在时为 {@code null}
     */
    private TriggerDef readTriggerOn(String table, String triggerName) {
        MysqlMetaTrigger probe = new MysqlMetaTrigger(metaData, engine);
        probe.tableName = table;
        return probe.get(triggerName);
    }
}
