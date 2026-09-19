package com.chua.postgresql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.TriggerCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.TriggerDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaTrigger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL 触发器元数据操作。
 * <p>
 * 读取路径为 {@code pg_trigger} + {@code pg_class} + {@code pg_namespace} + {@code pg_proc}：
 * {@code nspname = COALESCE(?, current_schema())}，已调用 {@code onTable(String)} 时追加
 * {@code cl.relname = ?}，按名字查询时追加 {@code tgname = ?}，三者都是绑定参数；
 * {@code tgisinternal} 为真的行（外键约束的 RI 触发器等内置触发器）不对外暴露。
 * </p>
 * <p>
 * {@code pg_trigger.tgtype} 是位掩码（ROW/BEFORE/INSERT/DELETE/UPDATE/TRUNCATE/AFTER/INSTEAD），
 * 由 Java 侧逐位解码，避免在 SQL 里写厂商版本相关的表达式；
 * 同一触发器的多事件（如 {@code INSERT OR UPDATE}）在 Java 侧按
 * {@code 模式.表名.触发器名} 归并成一条 {@link TriggerDef}，事件以 {@code INSERT OR UPDATE} 形式呈现。
 * </p>
 * <p>
 * 能力边界：
 * <ul>
 *   <li>{@link TriggerDef#getStatus()} 取自 {@code tgenabled}：{@code D} 为 {@code DISABLED}，
 *       {@code O}/{@code R}/{@code B} 均会触发故统一为 {@code ENABLED}，未知值保持 {@code null}。</li>
 *   <li>PostgreSQL 触发器必须先有返回 {@code trigger} 的函数，而 {@link TriggerCreateBuilder#body(String)}
 *       的语义（与 MySQL 的 {@code ACTION_STATEMENT} 一致）是"触发器语句体"，
 *       故 {@code create()} 自动生成 {@code <触发器名>_fn} 函数再 {@code CREATE TRIGGER} 绑定它；
 *       传入完整的 {@code CREATE FUNCTION} 语句会被显式拒绝。</li>
 *   <li>{@link TriggerDef} 没有事件多值与注释字段，多事件按 {@code OR} 拼接上报，
 *       {@code comment(String)} 只写不读（{@code COMMENT ON TRIGGER}）。</li>
 *   <li>{@code EXECUTE FUNCTION} 为 PostgreSQL 11+ 语法（旧写法 {@code EXECUTE PROCEDURE} 已废弃）。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PostgresqlMetaTrigger extends AbstractMetaTrigger {

    /**
     * {@code tgtype} 位掩码：逐行触发。
     */
    private static final int BIT_ROW = 0x01;
    /**
     * {@code tgtype} 位掩码：BEFORE。
     */
    private static final int BIT_BEFORE = 0x02;
    /**
     * {@code tgtype} 位掩码：INSERT。
     */
    private static final int BIT_INSERT = 0x04;
    /**
     * {@code tgtype} 位掩码：DELETE。
     */
    private static final int BIT_DELETE = 0x08;
    /**
     * {@code tgtype} 位掩码：UPDATE。
     */
    private static final int BIT_UPDATE = 0x10;
    /**
     * {@code tgtype} 位掩码：TRUNCATE。
     */
    private static final int BIT_TRUNCATE = 0x20;
    /**
     * {@code tgtype} 位掩码：AFTER。
     */
    private static final int BIT_AFTER = 0x40;
    /**
     * {@code tgtype} 位掩码：INSTEAD OF。
     */
    private static final int BIT_INSTEAD = 0x80;

    /**
     * 触发器查询：模式名绑定，表名/触发器名按需追加绑定条件。
     */
    private static final String TRIGGER_SQL =
            "SELECT current_database() AS trigger_catalog, n.nspname AS trigger_schema, tg.tgname AS trigger_name,"
                    + " cl.relname AS event_object_table, tg.tgtype AS trigger_type, tg.tgenabled AS trigger_enabled,"
                    + " p.proname AS trigger_function, p.prosrc AS action_statement"
                    + " FROM pg_catalog.pg_trigger tg"
                    + " JOIN pg_catalog.pg_class cl ON cl.oid = tg.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = cl.relnamespace"
                    + " JOIN pg_catalog.pg_proc p ON p.oid = tg.tgfoid"
                    + " WHERE NOT tg.tgisinternal AND n.nspname = COALESCE(?, current_schema())";

    /**
     * 触发器所属表查询，供 {@code drop}/{@code enable}/{@code disable} 在缺少表上下文时补齐。
     */
    private static final String TRIGGER_TABLE_SQL =
            "SELECT cl.relname AS event_object_table"
                    + " FROM pg_catalog.pg_trigger tg"
                    + " JOIN pg_catalog.pg_class cl ON cl.oid = tg.tgrelid"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = cl.relnamespace"
                    + " WHERE NOT tg.tgisinternal AND n.nspname = COALESCE(?, current_schema()) AND tg.tgname = ?"
                    + " LIMIT 1";

    /**
     * 构造方法（无触发器名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected PostgresqlMetaTrigger(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带触发器名上下文）。
     *
     * @param metaData    元数据入口
     * @param engine      引擎实例
     * @param triggerName 触发器名
     */
    protected PostgresqlMetaTrigger(AbstractMetaData metaData, Engine engine, String triggerName) {
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
        return new PostgresTriggerCreateBuilder(this, triggerName);
    }

    /**
     * 删除触发器（未指定表名时先按字典定位所属表）。
     *
     * @param triggerName 触发器名
     * @return 是否成功
     * @throws IllegalStateException 未指定触发器名、触发器不存在或执行失败
     */
    @Override
    public boolean drop(String triggerName) {
        String target = requireName(triggerName);
        String table = resolveTable(target);
        String sql = "DROP TRIGGER IF EXISTS " + PostgresqlMetaData.quote(target) + " ON " + qualifiedTable(table);
        return PostgresqlMetaData.execute(metaData, "删除触发器 " + target, sql, PostgresqlMetaData.args());
    }

    @Override
    public boolean enable(String triggerName) {
        return toggle(triggerName, "ENABLE");
    }

    @Override
    public boolean disable(String triggerName) {
        return toggle(triggerName, "DISABLE");
    }

    /**
     * 启用/禁用触发器。
     *
     * @param triggerName 触发器名
     * @param mode        {@code ENABLE} 或 {@code DISABLE}
     * @return 是否成功
     * @throws IllegalStateException 未指定触发器名、触发器不存在或执行失败
     */
    private boolean toggle(String triggerName, String mode) {
        String target = requireName(triggerName);
        String table = resolveTable(target);
        String sql = "ALTER TABLE " + qualifiedTable(table) + " " + mode + " TRIGGER "
                + PostgresqlMetaData.quote(target);
        return PostgresqlMetaData.execute(metaData, mode + " 触发器 " + target, sql, PostgresqlMetaData.args());
    }

    /**
     * 归一触发器名，允许使用构造期上下文。
     *
     * @param triggerName 传入的触发器名
     * @return 生效的触发器名
     * @throws IllegalStateException 两者均为空
     */
    private String requireName(String triggerName) {
        String target = triggerName != null ? triggerName : this.triggerName;
        if (target == null) {
            throw new IllegalStateException("未指定触发器名");
        }
        return target;
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
        List<Object> args = PostgresqlMetaData.args(PostgresqlMetaData.resolveSchema(metaData));
        if (tableName != null) {
            sql.append(" AND cl.relname = ?");
            args.add(tableName);
        }
        if (name != null) {
            sql.append(" AND tg.tgname = ?");
            args.add(name);
        }
        sql.append(" ORDER BY n.nspname, cl.relname, tg.tgname");
        List<TriggerRow> rows = PostgresqlMetaData.query(metaData,
                "查询触发器" + (name == null ? "" : " " + name), sql.toString(), args, TriggerRow::read);
        return group(rows);
    }

    /**
     * 定位触发器所属表：优先使用 {@code onTable(String)} 上下文，否则查字典。
     *
     * @param name 触发器名
     * @return 表名
     * @throws IllegalStateException 触发器不存在或查询失败
     */
    private String resolveTable(String name) {
        if (tableName != null) {
            return tableName;
        }
        String schema = PostgresqlMetaData.resolveSchema(metaData);
        String table = PostgresqlMetaData.queryOne(metaData, "定位触发器 " + name, TRIGGER_TABLE_SQL,
                PostgresqlMetaData.args(schema, name),
                rs -> PostgresqlMetaData.trimToNull(rs.getString("event_object_table")));
        if (table == null) {
            throw new IllegalStateException("触发器不存在: " + name
                    + "（模式过滤=" + (schema == null ? "current_schema()" : schema) + "）");
        }
        return table;
    }

    /**
     * 生成带模式前缀的表引用名；模式未指定时交给会话默认模式。
     *
     * @param table 表名
     * @return 引用后的表名
     */
    private String qualifiedTable(String table) {
        String schema = PostgresqlMetaData.resolveSchema(metaData);
        return schema == null ? PostgresqlMetaData.quote(table)
                : PostgresqlMetaData.quote(schema) + "." + PostgresqlMetaData.quote(table);
    }

    /**
     * 同一触发器的多事件行归并为一条定义。
     *
     * @param rows 原始行
     * @return 归并后的触发器定义列表
     */
    private static List<TriggerDef> group(List<TriggerRow> rows) {
        Map<String, TriggerDef> defs = new LinkedHashMap<>();
        Map<String, Set<String>> events = new LinkedHashMap<>();
        for (TriggerRow row : rows) {
            String key = row.key();
            TriggerDef def = defs.get(key);
            if (def == null) {
                def = new TriggerDef();
                def.setName(row.name());
                def.setCatalog(row.catalog());
                def.setSchema(row.schema());
                def.setTableName(row.tableName());
                def.setTiming(timingOf(row.type()));
                def.setForEachRow((row.type() & BIT_ROW) != 0);
                def.setBody(row.body());
                def.setStatus(statusOf(row.enabled()));
                defs.put(key, def);
                events.put(key, new LinkedHashSet<>());
            }
            events.get(key).addAll(eventOf(row.type()));
        }
        for (Map.Entry<String, TriggerDef> entry : defs.entrySet()) {
            Set<String> set = events.get(entry.getKey());
            entry.getValue().setEvent(set == null || set.isEmpty() ? null : String.join(" OR ", set));
        }
        return new ArrayList<>(defs.values());
    }

    /**
     * 解码 {@code tgtype} 的时机位。
     *
     * @param type 位掩码
     * @return {@code BEFORE} / {@code AFTER} / {@code INSTEAD OF}，无匹配时为 {@code null}
     */
    private static String timingOf(int type) {
        if ((type & BIT_BEFORE) != 0) {
            return "BEFORE";
        }
        if ((type & BIT_AFTER) != 0) {
            return "AFTER";
        }
        if ((type & BIT_INSTEAD) != 0) {
            return "INSTEAD OF";
        }
        return null;
    }

    /**
     * 解码 {@code tgtype} 的事件位。
     *
     * @param type 位掩码
     * @return 事件列表，按 INSERT / UPDATE / DELETE / TRUNCATE 顺序
     */
    private static List<String> eventOf(int type) {
        List<String> events = new ArrayList<>();
        if ((type & BIT_INSERT) != 0) {
            events.add("INSERT");
        }
        if ((type & BIT_UPDATE) != 0) {
            events.add("UPDATE");
        }
        if ((type & BIT_DELETE) != 0) {
            events.add("DELETE");
        }
        if ((type & BIT_TRUNCATE) != 0) {
            events.add("TRUNCATE");
        }
        return events;
    }

    /**
     * 归一 {@code tgenabled} 为模型状态。
     *
     * @param enabled 原始标志位字符
     * @return {@code ENABLED} / {@code DISABLED}，未知值返回 {@code null}
     */
    private static String statusOf(String enabled) {
        String value = PostgresqlMetaData.trimToNull(enabled);
        if (value == null || value.length() != 1) {
            return null;
        }
        return switch (value.charAt(0)) {
            case 'D' -> "DISABLED";
            case 'O', 'R', 'B' -> "ENABLED";
            default -> null;
        };
    }

    /**
     * 校验并归一触发事件。
     *
     * @param event 事件
     * @return 大写事件名
     */
    private static String checkEvent(String event) {
        String upper = event == null ? "" : event.trim().toUpperCase();
        if (!"INSERT".equals(upper) && !"UPDATE".equals(upper) && !"DELETE".equals(upper)
                && !"TRUNCATE".equals(upper)) {
            throw new IllegalArgumentException(
                    "PostgreSQL 触发事件仅支持 INSERT / UPDATE / DELETE / TRUNCATE，实际为: " + event);
        }
        return upper;
    }

    /**
     * {@code pg_trigger} 单行原始值。
     *
     * @author CH
     * @since 4.0.0
     */
    private record TriggerRow(String catalog, String schema, String name, String tableName, int type,
                              String enabled, String body) {

        /**
         * 读取结果集当前行，逐列对应 {@link TriggerDef} 属性来源。
         *
         * @param rs 结果集
         * @return 行值
         * @throws SQLException 读取失败
         */
        static TriggerRow read(ResultSet rs) throws SQLException {
            int type = rs.getInt("trigger_type");
            return new TriggerRow(PostgresqlMetaData.trimToNull(rs.getString("trigger_catalog")),
                    rs.getString("trigger_schema"), rs.getString("trigger_name"),
                    rs.getString("event_object_table"), type,
                    PostgresqlMetaData.trimToNull(rs.getString("trigger_enabled")),
                    rs.getString("action_statement"));
        }

        /**
         * 归并键：PostgreSQL 触发器名在同一表内唯一。
         *
         * @return {@code 模式.表名.触发器名}
         */
        String key() {
            return schema + "." + tableName + "." + name;
        }
    }

    /**
     * PostgreSQL 建触发器链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresTriggerCreateBuilder implements TriggerCreateBuilder {

        /**
         * 触发器函数名后缀
         */
        private static final String FUNCTION_SUFFIX = "_fn";

        /**
         * 所属触发器元数据入口
         */
        private final PostgresqlMetaTrigger metaTrigger;
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
         * 触发事件（可多值）
         */
        private final List<String> events = new ArrayList<>();
        /**
         * 逐行/逐语句，{@code null} 表示未显式指定
         */
        private Boolean forEachRow;
        /**
         * 触发器体
         */
        private final StringBuilder body = new StringBuilder();
        /**
         * 是否创建为禁用态
         */
        private boolean disabled;
        /**
         * 注释
         */
        private String comment;

        PostgresTriggerCreateBuilder(PostgresqlMetaTrigger metaTrigger, String triggerName) {
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
            return timing("BEFORE", event);
        }

        @Override
        public TriggerCreateBuilder after(String event) {
            return timing("AFTER", event);
        }

        @Override
        public TriggerCreateBuilder insteadOf(String event) {
            return timing("INSTEAD OF", event);
        }

        /**
         * 记录触发时机与事件，同一触发器只允许一个时机。
         *
         * @param value 时机
         * @param event 事件
         * @return this
         */
        private TriggerCreateBuilder timing(String value, String event) {
            String upper = checkEvent(event);
            if (timing == null) {
                timing = value;
            } else if (!timing.equals(value)) {
                throw new IllegalArgumentException(
                        "PostgreSQL 同一触发器只能有一个时机，已设置为 " + timing + "，无法再设为 " + value);
            }
            if ("TRUNCATE".equals(upper) && !"BEFORE".equals(timing)) {
                throw new IllegalArgumentException("PostgreSQL 的 TRUNCATE 触发器只能是 BEFORE 时机");
            }
            if ("INSTEAD OF".equals(timing) && "TRUNCATE".equals(upper)) {
                throw new IllegalArgumentException("PostgreSQL 的视图 INSTEAD OF 触发器不支持 TRUNCATE 事件");
            }
            if (!events.contains(upper)) {
                events.add(upper);
            }
            return this;
        }

        @Override
        public TriggerCreateBuilder forEachRow() {
            this.forEachRow = Boolean.TRUE;
            return this;
        }

        @Override
        public TriggerCreateBuilder forEachStatement() {
            this.forEachRow = Boolean.FALSE;
            return this;
        }

        @Override
        public TriggerCreateBuilder body(String body) {
            this.body.append(body);
            return this;
        }

        @Override
        public TriggerCreateBuilder enable() {
            this.disabled = false;
            return this;
        }

        @Override
        public TriggerCreateBuilder disable() {
            this.disabled = true;
            return this;
        }

        @Override
        public TriggerCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        /**
         * 执行建触发器语句。
         * <p>
         * 先 {@code CREATE OR REPLACE FUNCTION <触发器名>_fn() RETURNS trigger} 生成触发器函数，
         * 再 {@code CREATE TRIGGER ... EXECUTE FUNCTION} 绑定，必要时追加
         * {@code ALTER TABLE ... DISABLE TRIGGER} 与 {@code COMMENT ON TRIGGER}，
         * 最后以字典读回结果作为返回值。
         * </p>
         *
         * @return 落库后的触发器定义
         * @throws IllegalStateException 参数不完整或执行失败
         */
        @Override
        public TriggerDef execute() {
            if (tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 onTable(String)");
            }
            if (timing == null || events.isEmpty()) {
                throw new IllegalStateException("未指定触发时机和事件，请先调用 before(String)/after(String)/insteadOf(String)");
            }
            String raw = body.toString().trim();
            if (raw.isEmpty()) {
                throw new IllegalStateException("触发器体不能为空");
            }
            if (raw.regionMatches(true, 0, "CREATE", 0, "CREATE".length())) {
                throw new IllegalArgumentException("PostgreSQL 触发器体是语句序列，触发器函数由本实现自动生成，"
                        + "请勿传入 CREATE FUNCTION DDL");
            }
            String functionName = triggerName + FUNCTION_SUFFIX;
            PostgresqlMetaData.execute(metaTrigger.metaData, "创建触发器函数 " + functionName,
                    "CREATE OR REPLACE FUNCTION " + PostgresqlMetaData.quote(functionName) + "()"
                            + "\nRETURNS trigger\nLANGUAGE plpgsql AS "
                            + PostgresqlMetaData.dollarQuoted("触发器体", PostgresqlMetaData.wrapPlpgsql(raw)),
                    PostgresqlMetaData.args());
            String level = forEachRow == null || forEachRow ? "FOR EACH ROW" : "FOR EACH STATEMENT";
            String sql = "CREATE TRIGGER " + PostgresqlMetaData.quote(triggerName) + " " + timing + " "
                    + String.join(" OR ", events) + " ON " + metaTrigger.qualifiedTable(tableName)
                    + "\n" + level + "\nEXECUTE FUNCTION " + PostgresqlMetaData.quote(functionName);
            PostgresqlMetaData.execute(metaTrigger.metaData, "创建触发器 " + triggerName, sql,
                    PostgresqlMetaData.args());
            if (disabled) {
                PostgresqlMetaData.execute(metaTrigger.metaData, "禁用触发器 " + triggerName,
                        "ALTER TABLE " + metaTrigger.qualifiedTable(tableName) + " DISABLE TRIGGER "
                                + PostgresqlMetaData.quote(triggerName), PostgresqlMetaData.args());
            }
            if (comment != null && !comment.isEmpty()) {
                PostgresqlMetaData.execute(metaTrigger.metaData, "注释触发器 " + triggerName,
                        "COMMENT ON TRIGGER " + PostgresqlMetaData.quote(triggerName) + " ON "
                                + metaTrigger.qualifiedTable(tableName) + " IS '"
                                + PostgresqlMetaData.escapeSql(comment) + "'", PostgresqlMetaData.args());
            }
            return metaTrigger.readTriggerOn(tableName, triggerName);
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
        PostgresqlMetaTrigger probe = new PostgresqlMetaTrigger(metaData, engine);
        probe.tableName = table;
        return probe.get(triggerName);
    }
}
