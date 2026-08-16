package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.MetaTrigger;
import com.chua.common.support.lang.datasource.meta.TriggerCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.TriggerDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaTrigger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlMetaTrigger extends AbstractMetaTrigger {

    protected MysqlMetaTrigger(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    protected MysqlMetaTrigger(AbstractMetaData metaData, Engine engine, String triggerName) {
        super(metaData, engine, triggerName);
    }

    @Override
    public List<TriggerDef> list() {
        List<TriggerDef> result = new ArrayList<>();
        try (Connection conn = getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            String schemaPattern = metaData.getSchema() != null ? metaData.getSchema() : "%";
            try (ResultSet rs = dbMeta.getTables(metaData.getCatalog(), schemaPattern, "%", new String[]{"TRIGGER"})) {
                while (rs.next()) {
                    String name = rs.getString("TRIGGER_NAME");
                    String triggerSchema = rs.getString("TRIGGER_SCHEM");
                    TriggerDef def = getTriggerDefinition(conn, triggerSchema, name);
                    if (def != null) {
                        result.add(def);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("列出触发器失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public TriggerDef get(String triggerName) {
        try (Connection conn = getConnection()) {
            return getTriggerDefinition(conn, metaData.getSchema(), triggerName);
        } catch (Exception e) {
            throw new RuntimeException("获取触发器定义失败: " + triggerName, e);
        }
    }

    @Override
    public TriggerCreateBuilder create(String triggerName) {
        return new MysqlTriggerCreateBuilder(this, triggerName);
    }

    @Override
    public boolean drop(String triggerName) {
        return executeUpdate("DROP TRIGGER IF EXISTS " + quote(triggerName));
    }

    @Override
    public boolean enable(String triggerName) {
        return executeUpdate("ALTER TABLE " + quote(tableName) + " ENABLE TRIGGER `" + triggerName + "`");
    }

    @Override
    public boolean disable(String triggerName) {
        return executeUpdate("ALTER TABLE " + quote(tableName) + " DISABLE TRIGGER `" + triggerName + "`");
    }

    protected Connection getConnection() throws Exception {
        EngineDataSource<?> eds = engine.getDataSource(engine.getDefaultDataSourceName());
        if (eds == null) {
            throw new IllegalStateException("默认数据源未配置");
        }
        Object source = eds.getSource();
        if (source instanceof DataSource ds) {
            return ds.getConnection();
        }
        throw new IllegalStateException("数据源类型不支持 JDBC 连接获取: " + source.getClass().getName());
    }

    private TriggerDef getTriggerDefinition(Connection conn, String triggerSchema, String triggerName) throws Exception {
        String sql = "SHOW CREATE TRIGGER " + quote(triggerSchema != null ? triggerSchema + "." + triggerName : triggerName);
        try (java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                String createStmt = rs.getString("SQL Original Statement");
                TriggerDef def = new TriggerDef();
                def.setName(triggerName);
                def.setSchema(triggerSchema);
                def.setBody(createStmt);
                if (createStmt != null) {
                    String upper = createStmt.toUpperCase();
                    if (upper.contains("BEFORE")) {
                        def.setTiming("BEFORE");
                    } else if (upper.contains("AFTER")) {
                        def.setTiming("AFTER");
                    }
                    if (upper.contains("INSERT")) {
                        def.setEvent("INSERT");
                    } else if (upper.contains("UPDATE")) {
                        def.setEvent("UPDATE");
                    } else if (upper.contains("DELETE")) {
                        def.setEvent("DELETE");
                    }
                    def.setForEachRow(upper.contains("FOR EACH ROW"));
                }
                return def;
            }
        } catch (SQLException e) {
            String infoSchemaSql = "SELECT * FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_NAME = '" + triggerName + "'";
            if (triggerSchema != null) {
                infoSchemaSql += " AND TRIGGER_SCHEMA = '" + triggerSchema + "'";
            }
            try (java.sql.Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(infoSchemaSql)) {
                if (rs.next()) {
                    TriggerDef def = new TriggerDef();
                    def.setName(rs.getString("TRIGGER_NAME"));
                    def.setSchema(rs.getString("TRIGGER_SCHEMA"));
                    def.setTableName(rs.getString("EVENT_OBJECT_TABLE"));
                    def.setTiming(rs.getString("ACTION_TIMING"));
                    def.setEvent(rs.getString("EVENT_MANIPULATION"));
                    def.setBody(rs.getString("ACTION_STATEMENT"));
                    def.setForEachRow(true);
                    return def;
                }
            }
        }
        return null;
    }

    private String quote(String name) {
        return "`" + name + "`";
    }

    private boolean executeUpdate(String sql) {
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    private static class MysqlTriggerCreateBuilder implements TriggerCreateBuilder {

        private final MysqlMetaTrigger metaTrigger;
        private final String triggerName;
        private String tableName;
        private String timing;
        private String event;
        private boolean forEachRow = true;
        private String body;
        private boolean enable = true;
        private String comment;

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
            this.event = event;
            return this;
        }

        @Override
        public TriggerCreateBuilder after(String event) {
            this.timing = "AFTER";
            this.event = event;
            return this;
        }

        @Override
        public TriggerCreateBuilder insteadOf(String event) {
            throw new UnsupportedOperationException("MySQL 不支持 INSTEAD OF 触发器");
        }

        @Override
        public TriggerCreateBuilder forEachRow() {
            this.forEachRow = true;
            return this;
        }

        @Override
        public TriggerCreateBuilder forEachStatement() {
            this.forEachRow = false;
            return this;
        }

        @Override
        public TriggerCreateBuilder body(String body) {
            this.body = body;
            return this;
        }

        @Override
        public TriggerCreateBuilder enable() {
            this.enable = true;
            return this;
        }

        @Override
        public TriggerCreateBuilder disable() {
            this.enable = false;
            return this;
        }

        @Override
        public TriggerCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public TriggerDef execute() {
            if (tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 onTable()");
            }
            if (timing == null || event == null) {
                throw new IllegalStateException("未指定触发时机和事件，请先调用 before() 或 after()");
            }
            if (body == null || body.isEmpty()) {
                throw new IllegalStateException("触发器体不能为空");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TRIGGER ").append(metaTrigger.quote(triggerName)).append(" ");
            sb.append(timing).append(" ").append(event).append(" ON ").append(metaTrigger.quote(tableName)).append("\n");
            sb.append("FOR EACH ROW\n");
            sb.append("BEGIN\n");
            sb.append("  ").append(body.replace("\n", "\n  ")).append("\n");
            sb.append("END");
            metaTrigger.executeUpdate(sb.toString());
            TriggerDef def = new TriggerDef();
            def.setName(triggerName);
            def.setTableName(tableName);
            def.setTiming(timing);
            def.setEvent(event);
            def.setForEachRow(forEachRow);
            def.setBody(body);
            def.setStatus(enable ? "ENABLED" : "DISABLED");
            return def;
        }
    }
}
