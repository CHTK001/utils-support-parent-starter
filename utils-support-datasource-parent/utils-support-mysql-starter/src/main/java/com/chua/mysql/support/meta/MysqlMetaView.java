package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.MetaView;
import com.chua.common.support.lang.datasource.meta.ViewAlterBuilder;
import com.chua.common.support.lang.datasource.meta.ViewCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.ViewDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaView;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlMetaView extends AbstractMetaView {

    protected MysqlMetaView(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    protected MysqlMetaView(AbstractMetaData metaData, Engine engine, String viewName) {
        super(metaData, engine, viewName);
    }

    @Override
    public ViewCreateBuilder create(String viewName) {
        return new MysqlViewCreateBuilder(this, viewName);
    }

    @Override
    public ViewAlterBuilder alter() {
        return new MysqlViewAlterBuilder(this);
    }

    @Override
    public boolean drop() {
        if (viewName == null) {
            throw new IllegalStateException("未指定视图名");
        }
        return executeUpdate("DROP VIEW IF EXISTS " + quote(viewName));
    }

    protected String readViewDefinition(Connection conn, String schema, String viewName) throws Exception {
        String sql = "SHOW CREATE VIEW " + quote(schema != null ? schema + "." + viewName : viewName);
        try (java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString("Create View");
            }
        }
        return null;
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

    private static class MysqlViewCreateBuilder implements ViewCreateBuilder {

        private final MysqlMetaView metaView;
        private final String viewName;
        private String definition;
        private boolean orReplace;
        private String comment;
        private boolean updatable;

        MysqlViewCreateBuilder(MysqlMetaView metaView, String viewName) {
            this.metaView = metaView;
            this.viewName = viewName;
        }

        @Override
        public ViewCreateBuilder definition(String definition) {
            this.definition = definition;
            return this;
        }

        @Override
        public ViewCreateBuilder orReplace() {
            this.orReplace = true;
            return this;
        }

        @Override
        public ViewCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public ViewCreateBuilder updatable() {
            this.updatable = true;
            return this;
        }

        @Override
        public ViewCreateBuilder checkOption(String checkOption) {
            return this;
        }

        @Override
        public ViewDef execute() {
            if (definition == null || definition.isEmpty()) {
                throw new IllegalStateException("视图定义不能为空");
            }
            StringBuilder sb = new StringBuilder();
            if (orReplace) {
                sb.append("CREATE OR REPLACE VIEW ");
            } else {
                sb.append("CREATE VIEW ");
            }
            sb.append(metaView.quote(viewName));
            sb.append(" AS ").append(definition);
            metaView.executeUpdate(sb.toString());
            ViewDef def = new ViewDef();
            def.setName(viewName);
            def.setDefinition(definition);
            def.setCatalog(metaView.metaData.getCatalog());
            def.setSchema(metaView.metaData.getSchema());
            return def;
        }
    }

    private static class MysqlViewAlterBuilder implements ViewAlterBuilder {

        private final MysqlMetaView metaView;
        private String definition;
        private String newName;

        MysqlViewAlterBuilder(MysqlMetaView metaView) {
            this.metaView = metaView;
        }

        @Override
        public ViewAlterBuilder definition(String definition) {
            this.definition = definition;
            return this;
        }

        @Override
        public ViewAlterBuilder renameTo(String newName) {
            this.newName = newName;
            return this;
        }

        @Override
        public ViewDef execute() {
            if (newName != null && !newName.isEmpty()) {
                metaView.executeUpdate("RENAME TABLE " + metaView.quote(metaView.viewName) + " TO " + metaView.quote(newName));
                metaView.viewName = newName;
            }
            if (definition != null && !definition.isEmpty()) {
                String sql = "CREATE OR REPLACE VIEW " + metaView.quote(metaView.viewName) + " AS " + definition;
                metaView.executeUpdate(sql);
            }
            ViewDef def = new ViewDef();
            def.setName(metaView.viewName);
            def.setDefinition(definition);
            return def;
        }
    }
}
