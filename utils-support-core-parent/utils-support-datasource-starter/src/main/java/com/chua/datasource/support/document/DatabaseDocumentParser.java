package com.chua.datasource.support.document;

import com.chua.common.support.lang.document.*;
import com.chua.common.support.spi.annotations.Spi;

import java.sql.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据库文档解析器 — 通过 JDBC 元数据读取表结构及外键关系。
 *
 * <p>SPI 名称为 {@code "database"}，接受 {@link DocumentConfig} 配置 JDBC 连接。</p>
 *
 * <p>配置项（通过 {@link DocumentConfig#getOptions()}）：</p>
 * <ul>
 *   <li>{@code schemas} — 逗号分隔的 schema 名列表，或 {@code "*"} 表示全部（默认全部）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("database")
@Slf4j
public class DatabaseDocumentParser implements DocumentParser {

@Override
    public DocumentData parse(DocumentConfig config) {
        String url = config.getUrl();
        String username = config.getUsername();
        String password = config.getPassword();
        String driverClass = config.getDriverClass();

        Set<String> schemas = resolveSchemas(config);
        boolean allMode = isAllMode(config);

            if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("JDBC driver not found: " + driverClass, e);
            }
        }

        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            DatabaseMetaData meta = conn.getMetaData();
            String dbVersion = meta.getDatabaseProductVersion();
            String docVersion = resolveVersion(config);

            List<TableData> tables = new ArrayList<>();
            Set<String> schemaNames = new LinkedHashSet<>();

            List<String> catalogs = new ArrayList<>();
            if (allMode) {
                try (ResultSet rsCatalogs = meta.getCatalogs()) {
                    while (rsCatalogs.next()) {
                        catalogs.add(rsCatalogs.getString(1));
                    }
                }
                if (catalogs.isEmpty()) {
                    catalogs.add(null);
                }
            } else {
                catalogs.add(extractCatalog(url));
            }

            for (String cat : catalogs) {
                try (ResultSet rsTables = meta.getTables(cat, null, "%", null)) {
                    while (rsTables.next()) {
                        String tableName = rsTables.getString("TABLE_NAME");
                        String tableType = rsTables.getString("TABLE_TYPE");
                        String tableSchema = rsTables.getString("TABLE_SCHEM");
                        String tableCatalog = rsTables.getString("TABLE_CAT");
                        String remarks = rsTables.getString("REMARKS");

                        if (tableName == null) continue;
                        if (!"TABLE".equals(tableType) && !"VIEW".equals(tableType) && !"SYSTEM TABLE".equals(tableType)) continue;

                        if (tableSchema == null || tableSchema.isBlank()) {
                            tableSchema = tableCatalog;
                        }
                        if (tableSchema == null || tableSchema.isBlank()) {
                            tableSchema = "(unknown)";
                        }

                        if (schemas != null && !schemas.contains(tableSchema)) continue;
                        if (!allMode && cat != null && !cat.isBlank()
                                && !cat.equalsIgnoreCase(tableSchema)
                                && !cat.equalsIgnoreCase(tableCatalog)) continue;
                        schemaNames.add(tableSchema);

                        TableData.TableDataBuilder builder = TableData.builder()
                                .tableName(tableName)
                                .schema(tableSchema)
                                .remark(remarks != null ? remarks : "")
                                .type(tableType);

                        List<ColumnData> columns = new ArrayList<>();
                        try (ResultSet cols = meta.getColumns(tableCatalog, tableSchema, tableName, "%")) {
                            while (cols.next()) {
                                String colName = cols.getString("COLUMN_NAME");
                                String typeName = cols.getString("TYPE_NAME");
                                int colSize = cols.getInt("COLUMN_SIZE");
                                int decimalDigits = cols.getInt("DECIMAL_DIGITS");
                                if (cols.wasNull()) decimalDigits = -1;
                                int nullable = cols.getInt("NULLABLE");
                                String defaultValue = cols.getString("COLUMN_DEF");
                                String colRemark = cols.getString("REMARKS");
                                int ordinal = cols.getInt("ORDINAL_POSITION");

                                columns.add(ColumnData.builder()
                                        .ordinalPosition(ordinal)
                                        .columnName(colName)
                                        .typeName(typeName)
                                        .columnSize(colSize)
                                        .decimalDigits(decimalDigits >= 0 ? decimalDigits : null)
                                        .nullable(nullable == DatabaseMetaData.columnNullable)
                                        .primaryKey(false)
                                        .defaultValue(defaultValue)
                                        .remark(colRemark != null ? colRemark : "")
                                        .build());
                            }
                        }

                        try (ResultSet pk = meta.getPrimaryKeys(tableCatalog, tableSchema, tableName)) {
                            while (pk.next()) {
                                String pkCol = pk.getString("COLUMN_NAME");
                                for (int i = 0; i < columns.size(); i++) {
                                    ColumnData c = columns.get(i);
                                    if (c.getColumnName().equals(pkCol)) {
                                        columns.set(i, toBuilder(c).primaryKey(true).build());
                                    }
                                }
                            }
                        }
                        builder.columns(columns);

                        List<RelationshipData> importedKeys = new ArrayList<>();
                        try (ResultSet fk = meta.getImportedKeys(tableCatalog, tableSchema, tableName)) {
                            while (fk.next()) {
                                importedKeys.add(RelationshipData.builder()
                                        .fkName(fk.getString("FK_NAME"))
                                        .fkTableName(fk.getString("FKTABLE_NAME"))
                                        .fkColumnName(fk.getString("FKCOLUMN_NAME"))
                                        .pkTableName(fk.getString("PKTABLE_NAME"))
                                        .pkColumnName(fk.getString("PKCOLUMN_NAME"))
                                        .updateRule(resolveRule(fk.getShort("UPDATE_RULE")))
                                        .deleteRule(resolveRule(fk.getShort("DELETE_RULE")))
                                        .build());
                            }
                        }
                        builder.importedKeys(importedKeys);

                        List<RelationshipData> exportedKeys = new ArrayList<>();
                        try (ResultSet fk = meta.getExportedKeys(tableCatalog, tableSchema, tableName)) {
                            while (fk.next()) {
                                exportedKeys.add(RelationshipData.builder()
                                        .fkName(fk.getString("FK_NAME"))
                                        .fkTableName(fk.getString("FKTABLE_NAME"))
                                        .fkColumnName(fk.getString("FKCOLUMN_NAME"))
                                        .pkTableName(fk.getString("PKTABLE_NAME"))
                                        .pkColumnName(fk.getString("PKCOLUMN_NAME"))
                                        .updateRule(resolveRule(fk.getShort("UPDATE_RULE")))
                                        .deleteRule(resolveRule(fk.getShort("DELETE_RULE")))
                                        .build());
                            }
                        }
                        builder.exportedKeys(exportedKeys);

                        tables.add(builder.build());
                    }
                }
            }

            String dbName = buildDbName(allMode ? null : extractCatalog(url), schemaNames);
            String title = schemaNames.size() == 1
                    ? dbName + " 数据库设计文档"
                    : "数据库设计文档（" + schemaNames.size() + " 个 schema）";

            return DocumentData.builder()
                    .databaseName(dbName)
                    .productName(meta.getDatabaseProductName())
                    .productVersion(dbVersion)
                    .url(url)
                    .title(title)
                    .description("共 " + schemaNames.size() + " 个 schema，"
                            + tables.size()                     + "，共 " + schemaNames.size() + " 个 schema，"
                            + tables.size() + " 张表")
                    .version(docVersion)
                    .tables(tables)
                    .build();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to parse database metadata: " + url, e);
        }
    }

    private static boolean isAllMode(DocumentConfig config) {
        if (config.getOptions() == null) return false;
        Object raw = config.getOptions().get("all");
        if (raw == null) return false;
        if (raw instanceof Boolean) return (Boolean) raw;
        return "true".equalsIgnoreCase(raw.toString().trim());
    }

    private static Set<String> resolveSchemas(DocumentConfig config) {
        if (config.getOptions() == null) return null;
        Object raw = config.getOptions().get("schemas");
        if (raw == null) return null;
        String val = raw.toString().trim();
        if (val.isEmpty() || "*".equals(val)) return null;
        Set<String> set = new LinkedHashSet<>();
        for (String s : val.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set.isEmpty() ? null : set;
    }

    /**
     * 从配置中读取版本号，未设置时返回默认值。
     *
     * @param config 文档配置
     * @return 版本号字符串
     */
    private static String resolveVersion(DocumentConfig config) {
        if (config.getOptions() == null) {
            return "1.0.0";
        }
        Object raw = config.getOptions().get("version");
        if (raw == null || raw.toString().isBlank()) {
            return "1.0.0";
        }
        return raw.toString().trim();
    }

    private static String extractCatalog(String url) {
        int idx = url.indexOf('?');
        String base = idx > 0 ? url.substring(0, idx) : url;
        int lastSlash = base.lastIndexOf('/');
        if (lastSlash > 0) {
            String db = base.substring(lastSlash + 1);
            if (!db.isEmpty() && !db.equals(url.substring(lastSlash - 1, lastSlash))) {
                return db;
            }
        }
        return null;
    }

    private static String buildDbName(String catalog, Set<String> schemas) {
        if (schemas.size() == 1) {
            return schemas.iterator().next();
        }
        if (catalog != null && !catalog.isEmpty()) {
            return catalog;
        }
        return "MULTI";
    }

    private static ColumnData.ColumnDataBuilder toBuilder(ColumnData c) {
        return ColumnData.builder()
                .ordinalPosition(c.getOrdinalPosition())
                .columnName(c.getColumnName())
                .typeName(c.getTypeName())
                .columnSize(c.getColumnSize())
                .decimalDigits(c.getDecimalDigits())
                .nullable(c.isNullable())
                .primaryKey(c.isPrimaryKey())
                .defaultValue(c.getDefaultValue())
                .remark(c.getRemark());
    }

    private static String resolveRule(short rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case DatabaseMetaData.importedKeyNoAction -> "NO ACTION";
            default -> "UNKNOWN";
        };
    }
}
