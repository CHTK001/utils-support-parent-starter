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

    /**
      * 创建 mysqlmetaprocedure 实例
     * @param metaData meta数据
     * @param engine Engine
     * @param engine engine
     */
    protected MysqlMetaProcedure(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
      * 创建 mysqlmetaprocedure 实例
     * @param metaData meta数据
     * @param engine Engine
     * @param procedureName 字符串
     * @param engine engine
     * @param procedureName procedure名称
     */
    protected MysqlMetaProcedure(AbstractMetaData metaData, Engine engine, String procedureName) {
        super(metaData, engine, procedureName);
    }

    @Override
    /** 列表 */
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
    /** 获取 */
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
    /** 创建 */
    public ProcedureCreateBuilder create(String procedureName) {
        return new MysqlProcedureCreateBuilder(this, procedureName);
    }

    @Override
    /** 掉落 */
    public boolean drop(String procedureName) {
        return executeUpdate("DROP PROCEDURE IF EXISTS `" + procedureName + "`");
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

    private static class MysqlProcedureCreateBuilder implements ProcedureCreateBuilder {

        /** Metaprocedure */
        private final MysqlMetaProcedure metaProcedure;
        /** Procedure名称 */
        private final String procedureName;
        /** 参数 */
        private final StringBuilder params = new StringBuilder();
        /** 请求体 */
        private final StringBuilder body = new StringBuilder();
        /** 语言 */
        private String language = "SQL";
        /** 安全性类型 */
        private String securityType;
        /** 评论 */
        private String comment;

        MysqlProcedureCreateBuilder(MysqlMetaProcedure metaProcedure, String procedureName) {
            this.metaProcedure = metaProcedure;
            this.procedureName = procedureName;
        }

        @Override
        /** 入 */
        public ProcedureCreateBuilder in(String name, String type) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append("IN `").append(name).append("` ").append(type);
            return this;
        }

        @Override
        /** 出 */
        public ProcedureCreateBuilder out(String name, String type) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append("OUT `").append(name).append("` ").append(type);
            return this;
        }

        @Override
        /** Inout */
        public ProcedureCreateBuilder inout(String name, String type) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append("INOUT `").append(name).append("` ").append(type);
            return this;
        }

        @Override
        /** 参数 */
        public ProcedureCreateBuilder param(String name, String type, String direction) {
            if (params.length() > 0) {
                params.append(", ");
            }
            params.append(direction).append(" `").append(name).append("` ").append(type);
            return this;
        }

        @Override
        /** 主体 */
        public ProcedureCreateBuilder body(String body) {
            this.body.append(body);
            return this;
        }

        @Override
        /** Language */
        public ProcedureCreateBuilder language(String language) {
            this.language = language;
            return this;
        }

        @Override
        /** 安全性类型 */
        public ProcedureCreateBuilder securityType(String securityType) {
            this.securityType = securityType;
            return this;
        }

        @Override
        /** 评论 */
        public ProcedureCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        /** 或替换 */
        public ProcedureCreateBuilder orReplace() {
            return this;
        }

        @Override
        /** 执行 */
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
