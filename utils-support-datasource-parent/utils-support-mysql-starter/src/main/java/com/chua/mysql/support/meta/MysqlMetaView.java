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

    /**
     * 创建 mysqlmetaview 实例
     * @param metaData meta数据
     * @param engine Engine
     * @param engine engine
     */
    protected MysqlMetaView(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 创建 mysqlmetaview 实例
     * @param metaData meta数据
     * @param engine Engine
     * @param viewName 字符串
     * @param engine engine
     * @param viewName view名称
     */
    protected MysqlMetaView(AbstractMetaData metaData, Engine engine, String viewName) {
        super(metaData, engine, viewName);
    }

    @Override
    /** 创建 */
    public ViewCreateBuilder create(String viewName) {
        return new MysqlViewCreateBuilder(this, viewName);
    }

    @Override
    /** Alter */
    public ViewAlterBuilder alter() {
        return new MysqlViewAlterBuilder(this);
    }

    @Override
    /** 掉落 */
    public boolean drop() {
        if (viewName == null) {
            throw new IllegalStateException("未指定视图名");
        }
        return executeUpdate("DROP VIEW IF EXISTS " + quote(viewName));
    }

    @Override
    /** 列表 */
    public List<ViewDef> list() {
        List<ViewDef> result = new ArrayList<>();
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT TABLE_NAME, VIEW_DEFINITION"
                             + " FROM INFORMATION_SCHEMA.VIEWS"
                             + " WHERE TABLE_SCHEMA = DATABASE()")) {
            while (rs.next()) {
                ViewDef def = new ViewDef();
                def.setName(rs.getString("TABLE_NAME"));
                def.setDefinition(rs.getString("VIEW_DEFINITION"));
                result.add(def);
            }
        } catch (Exception e) {
            throw new RuntimeException("列表面视图失败", e);
        }
        return result;
    }

    @Override
    /** 单查 */
    public ViewDef get() {
        if (viewName == null) {
            throw new IllegalStateException("未指定视图名");
        }
        try (Connection conn = getConnection()) {
            String definition = readViewDefinition(conn, null, viewName);
            if (definition == null) {
                return null;
            }
            ViewDef def = new ViewDef();
            def.setName(viewName);
            def.setDefinition(definition);
            def.setCatalog(metaData.getCatalog());
            def.setSchema(metaData.getSchema());
            return def;
        } catch (Exception e) {
            throw new RuntimeException("查询视图失败: " + viewName, e);
        }
    }

    /**
    * 读取viewdefinition
    *
    * @param conn conn
    * @param schema 模式
    * @param viewName view名称
    * @return 读取viewdefinition的结果
    */
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

    /**
     * 获取Connection
     *
     * @return 获取connection的结果
     */
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

    /**
     * 引述
     *
     * @param name 名称
     * @return 引述的结果
     */
    private String quote(String name) {
        return "`" + name + "`";
    }

    /**
     * 执行更新
     *
     * @param sql SQL
     * @return 执行更新的结果
     * @author CH
     * @since 4.0.0
     */
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

        /** Metaview */
        private final MysqlMetaView metaView;
        /** View名称 */
        private final String viewName;
        /** Definition */
        private String definition;
        /** orreplace */
        private boolean orReplace;
        /** 评论 */
        private String comment;
        /** Updatable */
        private boolean updatable;

        MysqlViewCreateBuilder(MysqlMetaView metaView, String viewName) {
            this.metaView = metaView;
            this.viewName = viewName;
        }

        @Override
        /** Definition */
        public ViewCreateBuilder definition(String definition) {
            this.definition = definition;
            return this;
        }

        @Override
        /** 或替换 */
        public ViewCreateBuilder orReplace() {
            this.orReplace = true;
            return this;
        }

        @Override
        /** 评论 */
        public ViewCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        /** Updatable */
        public ViewCreateBuilder updatable() {
            this.updatable = true;
            return this;
        }

        @Override
        /** 校验期权 */
        public ViewCreateBuilder checkOption(String checkOption) {
            return this;
        }

        @Override
        /**
        * 执行
        *
        * @return 执行的结果
        * @author CH
        * @since 4.0.0
        */
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

        /** Metaview */
        private final MysqlMetaView metaView;
        /** Definition */
        private String definition;
        /** 新名称 */
        private String newName;

        MysqlViewAlterBuilder(MysqlMetaView metaView) {
            this.metaView = metaView;
        }

        @Override
        /** Definition */
        public ViewAlterBuilder definition(String definition) {
            this.definition = definition;
            return this;
        }

        @Override
        /** 重命名转为 */
        public ViewAlterBuilder renameTo(String newName) {
            this.newName = newName;
            return this;
        }

        @Override
        /** 执行 */
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
