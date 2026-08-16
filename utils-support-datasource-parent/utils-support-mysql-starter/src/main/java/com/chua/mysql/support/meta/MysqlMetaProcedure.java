package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.MetaProcedure;
import com.chua.common.support.lang.datasource.meta.ProcedureCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.ProcedureDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaProcedure;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MysqlMetaProcedure extends AbstractMetaProcedure {

    protected MysqlMetaProcedure(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    protected MysqlMetaProcedure(AbstractMetaData metaData, Engine engine, String procedureName) {
        super(metaData, engine, procedureName);
    }

    @Override
    public List<ProcedureDef> list() {
        List<ProcedureDef> result = new ArrayList<>();
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement();
              ResultSet rs = stmt.executeQuery("SHOW PROCEDURE STATUS WHERE Db = '" + metaData.getCatalog() + "'")) {
            while (rs.next()) {
                ProcedureDef def = new ProcedureDef();
                def.setName(rs.getString("Name"));
                def.setCatalog(rs.getString("Db"));
                def.setLanguage(rs.getString("Body"));
                result.add(def);
            }
        } catch (Exception e) {
            throw new RuntimeException("列出存储过程失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public ProcedureDef get(String procedureName) {
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW CREATE PROCEDURE `" + procedureName + "`")) {
            if (rs.next()) {
                ProcedureDef def = new ProcedureDef();
                def.setName(procedureName);
                def.setBody(rs.getString("Create Procedure"));
                return def;
            }
        } catch (Exception e) {
            throw new RuntimeException("获取存储过程定义失败: " + procedureName, e);
        }
        return null;
    }

    @Override
    public ProcedureCreateBuilder create(String procedureName) {
        return new MysqlProcedureCreateBuilder(this, procedureName);
    }

    @Override
    public boolean drop(String procedureName) {
        return executeUpdate("DROP PROCEDURE IF EXISTS `" + procedureName + "`");
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

    private boolean executeUpdate(String sql) {
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }

    private static class MysqlProcedureCreateBuilder implements ProcedureCreateBuilder {

        private final MysqlMetaProcedure metaProcedure;
        private final String procedureName;
        private final StringBuilder params = new StringBuilder();
        private final StringBuilder body = new StringBuilder();
        private String language = "SQL";
        private String securityType;
        private String comment;

        MysqlProcedureCreateBuilder(MysqlMetaProcedure metaProcedure, String procedureName) {
            this.metaProcedure = metaProcedure;
            this.procedureName = procedureName;
        }

        @Override
        public ProcedureCreateBuilder in(String name, String type) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append("IN `").append(name).append("` ").append(type);
            return this;
        }

        @Override
        public ProcedureCreateBuilder out(String name, String type) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append("OUT `").append(name).append("` ").append(type);
            return this;
        }

        @Override
        public ProcedureCreateBuilder inout(String name, String type) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append("INOUT `").append(name).append("` ").append(type);
            return this;
        }

        @Override
        public ProcedureCreateBuilder param(String name, String type, String direction) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append(direction).append(" `").append(name).append("` ").append(type);
            return this;
        }

        @Override
        public ProcedureCreateBuilder body(String body) {
            this.body.append(body);
            return this;
        }

        @Override
        public ProcedureCreateBuilder language(String language) {
            this.language = language;
            return this;
        }

        @Override
        public ProcedureCreateBuilder securityType(String securityType) {
            this.securityType = securityType;
            return this;
        }

        @Override
        public ProcedureCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public ProcedureCreateBuilder orReplace() {
            return this;
        }

        @Override
        public ProcedureDef execute() {
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE PROCEDURE `").append(procedureName).append("`(");
            sb.append(params.toString());
            sb.append(")\n");
            if (securityType != null && !securityType.isEmpty()) {
                sb.append("SQL SECURITY ").append(securityType).append("\n");
            }
            sb.append("BEGIN\n");
            sb.append("  ").append(body.toString().replace("\n", "\n  ")).append("\n");
            sb.append("END");
            metaProcedure.executeUpdate(sb.toString());
            ProcedureDef def = new ProcedureDef();
            def.setName(procedureName);
            def.setBody(body.toString());
            def.setLanguage(language);
            def.setSecurityType(securityType);
            def.setComment(comment);
            return def;
        }
    }
}
