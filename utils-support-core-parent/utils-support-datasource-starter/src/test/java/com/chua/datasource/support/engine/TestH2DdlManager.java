package com.chua.datasource.support.engine;

import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.datasource.support.ddl.DslManager;
import com.chua.datasource.support.user.DataSourceAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用 H2 DDL 管理器 SPI 实现（验证 engine.ddl() 解析与 DataSource 注入）。
 */
public class TestH2DdlManager implements DslManager, DataSourceAware {

    private DataSource dataSource;

    @Override
    public String type() {
        return "h2";
    }

    @Override
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public TableDef getTable(String catalogName, String schemaName, String tableName) {
        for (TableDef def : listTables(catalogName, schemaName)) {
            if (def.getName().equalsIgnoreCase(tableName)) {
                return def;
            }
        }
        return null;
    }

    @Override
    public String createTableDDL(String catalogName, String schemaName, String tableName) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(tableName).append(" (\n");
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.getMetaData().getColumns(catalogName, schemaName, tableName, "%")) {
            boolean first = true;
            while (rs.next()) {
                if (!first) {
                    ddl.append(",\n");
                }
                first = false;
                ddl.append("  ").append(rs.getString("COLUMN_NAME"))
                        .append(' ').append(rs.getString("TYPE_NAME"));
                int size = rs.getInt("COLUMN_SIZE");
                if (size > 0 && !rs.getString("TYPE_NAME").equalsIgnoreCase("INTEGER")) {
                    ddl.append('(').append(size).append(')');
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("createTableDDL failed: " + e.getMessage(), e);
        }
        return ddl.append("\n);").toString();
    }

    @Override
    public String renameTable(String schemaName, String oldTableName, String newTableName) {
        return "ALTER TABLE " + oldTableName + " RENAME TO " + newTableName + ";";
    }

    @Override
    public String copyTableStructure(String schemaName, String sourceTableName, String targetTableName) {
        return "CREATE TABLE " + targetTableName + " AS SELECT * FROM " + sourceTableName + " WHERE 1 = 0;";
    }

    @Override
    public List<TableDef> listTables(String catalogName, String schemaName) {
        List<TableDef> tables = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getTables(catalogName, schemaName, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    TableDef def = new TableDef();
                    def.setName(rs.getString("TABLE_NAME"));
                    def.setCatalog(rs.getString("TABLE_CAT"));
                    def.setSchema(rs.getString("TABLE_SCHEM"));
                    def.setType(rs.getString("TABLE_TYPE"));
                    tables.add(def);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("listTables failed: " + e.getMessage(), e);
        }
        return tables;
    }
}
