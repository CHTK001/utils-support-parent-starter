package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.ForeignKeyCreateBuilder;
import com.chua.common.support.lang.datasource.meta.MetaForeignKey;
import com.chua.common.support.lang.datasource.meta.model.ForeignKeyDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaForeignKey;

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

public class MysqlMetaForeignKey extends AbstractMetaForeignKey {

    /**
      * 创建 mysqlmeta国外键 实例
     * @param metaData meta数据
     * @param engine Engine
     * @param engine engine
     */
    protected MysqlMetaForeignKey(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
      * 创建 mysqlmeta国外键 实例
     * @param metaData meta数据
     * @param engine Engine
     * @param fkName 字符串
     * @param engine engine
     * @param fkName fk名称
     */
    protected MysqlMetaForeignKey(AbstractMetaData metaData, Engine engine, String fkName) {
        super(metaData, engine, fkName);
    }

    @Override
    /** 列表 */
    public List<ForeignKeyDef> list() {
        List<ForeignKeyDef> result = new ArrayList<>();
        if (tableName == null) {
            return result;
        }
        try (Connection conn = getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            try (ResultSet rs = dbMeta.getImportedKeys(metaData.getCatalog(), metaData.getSchema(), tableName)) {
                while (rs.next()) {
                    ForeignKeyDef def = new ForeignKeyDef();
                    def.setName(rs.getString("FK_NAME"));
                    def.setTableName(rs.getString("FKTABLE_NAME"));
                    def.setColumnName(rs.getString("FKCOLUMN_NAME"));
                    def.setRefTableName(rs.getString("PKTABLE_NAME"));
                    def.setRefColumnName(rs.getString("PKCOLUMN_NAME"));
                    short deleteRule = rs.getShort("DELETE_RULE");
                    short updateRule = rs.getShort("UPDATE_RULE");
                    def.setOnDelete(resolveRule(deleteRule));
                    def.setOnUpdate(resolveRule(updateRule));
                    result.add(def);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("列出外键失败: " + tableName, e);
        }
        return result;
    }

    @Override
    /** 获取 */
    public ForeignKeyDef get(String fkName) {
        List<ForeignKeyDef> all = list();
        return all.stream()
                .filter(fk -> fkName.equals(fk.getName()))
                .findFirst()
                .orElse(null);
    }

    @Override
    /** 添加 */
    public ForeignKeyCreateBuilder add(String fkName) {
        return new MysqlForeignKeyCreateBuilder(this, fkName);
    }

    @Override
    /** 掉落 */
    public boolean drop(String fkName) {
        return executeUpdate("ALTER TABLE " + quote(tableName) + " DROP FOREIGN KEY " + quote(fkName));
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

    /**
     * 解析Rule
     *
     * @param rule rule
     * @return resolveRule的结果
     * @author CH
     * @since 4.0.0
     */
    private static String resolveRule(short rule) {
        return switch (rule) {
            case java.sql.DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case java.sql.DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case java.sql.DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case java.sql.DatabaseMetaData.importedKeyNoAction -> "NO ACTION";
            default -> "NO ACTION";
        };
    }

    private static class MysqlForeignKeyCreateBuilder implements ForeignKeyCreateBuilder {

        /** metafk */
        private final MysqlMetaForeignKey metaFk;
        /** FK名称 */
        private final String fkName;
        /** 列名称 */
        private String columnName;
        /** 引用表 */
        private String refTable;
        /** 引用列 */
        private String refColumn;
        /** ondelete */
        private String onDelete;
        /** onupdate */
        private String onUpdate;

        MysqlForeignKeyCreateBuilder(MysqlMetaForeignKey metaFk, String fkName) {
            this.metaFk = metaFk;
            this.fkName = fkName;
        }

        @Override
        /** Column */
        public ForeignKeyCreateBuilder column(String columnName) {
            this.columnName = columnName;
            return this;
        }

        @Override
        /** 引用 */
        public ForeignKeyCreateBuilder references(String table, String column) {
            this.refTable = table;
            this.refColumn = column;
            return this;
        }

        @Override
        /** On删除 */
        public ForeignKeyCreateBuilder onDelete(String action) {
            this.onDelete = action;
            return this;
        }

        @Override
        /** On更新 */
        public ForeignKeyCreateBuilder onUpdate(String action) {
            this.onUpdate = action;
            return this;
        }

        @Override
        /** 执行 */
        public ForeignKeyDef execute() {
            if (metaFk.tableName == null) {
                throw new IllegalStateException("未指定表名，请先调用 onTable()");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("ALTER TABLE ").append(metaFk.quote(metaFk.tableName)).append("\n");
            sb.append("  ADD CONSTRAINT ").append(metaFk.quote(fkName)).append("\n");
            sb.append("  FOREIGN KEY (`").append(columnName).append("`)\n");
            sb.append("  REFERENCES `").append(refTable).append("` (`").append(refColumn).append("`)");
            if (onDelete != null && !onDelete.isEmpty()) {
                sb.append("\n  ON DELETE ").append(onDelete);
            }
            if (onUpdate != null && !onUpdate.isEmpty()) {
                sb.append("\n  ON UPDATE ").append(onUpdate);
            }
            metaFk.executeUpdate(sb.toString());
            ForeignKeyDef def = new ForeignKeyDef();
            def.setName(fkName);
            def.setTableName(metaFk.tableName);
            def.setColumnName(columnName);
            def.setRefTableName(refTable);
            def.setRefColumnName(refColumn);
            def.setOnDelete(onDelete);
            def.setOnUpdate(onUpdate);
            return def;
        }
    }
}
