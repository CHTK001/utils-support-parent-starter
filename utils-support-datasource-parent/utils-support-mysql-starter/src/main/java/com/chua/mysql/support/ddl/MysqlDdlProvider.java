package com.chua.mysql.support.ddl;

import com.chua.common.support.lang.datasource.engine.ddl.ColumnDef;
import com.chua.common.support.lang.datasource.engine.ddl.DdlProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MySQL 方言 DDL 生成器。
 *
 * <p>建库固定 utf8mb4 + unicode_ci 排序规则；建表支持 AUTO_INCREMENT、
 * 列注释（COMMENT '{comment}'）与联合主键。标识符使用反引号包裹，
 * 内部反引号按 MySQL 规则双写转义，阻断 DDL 注入。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi(DdlProvider.SPI_NAME)
public class MysqlDdlProvider implements DdlProvider {

    /**
     * 本实现覆盖的方言协议。
     */
    private static final Set<String> PROTOCOLS = Set.of("mysql", "mariadb", "mysql57");

    /**
     * 判断是否支持给定数据库方言协议。
     *
     * @param protocol 方言协议名
     * @return 是否支持该协议
     */
    @Override
    public boolean supports(String protocol) {
        return protocol != null && PROTOCOLS.contains(protocol);
    }

    /**
     * 生成建库语句。
     *
     * @param dbName 数据库名称
     * @return 完整的建库 DDL 语句
     */
    @Override
    public String createDatabase(String dbName) {
        return "CREATE DATABASE IF NOT EXISTS " + quote(dbName)
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
    }

    /**
     * 生成删库语句。
     *
     * @param dbName 数据库名称
     * @return 完整的删库 DDL 语句
     */
    @Override
    public String dropDatabase(String dbName) {
        return "DROP DATABASE IF EXISTS " + quote(dbName);
    }

    /**
     * 生成建表语句。
     *
     * @param tableName 表名
     * @param columns   列定义列表
     * @return 完整的建表 DDL 语句
     */
    @Override
    public String createTable(String tableName, List<ColumnDef> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("建表列定义不能为空: " + tableName);
        }
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(quote(tableName)).append(" (\n");
        List<String> pk = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDef col = columns.get(i);
            if (col.getName() == null || col.getName().isBlank()) {
                throw new IllegalArgumentException("建表列名不能为空: " + tableName);
            }
            sb.append("  ").append(quote(col.getName())).append(' ')
                    .append(col.getType() == null || col.getType().isBlank() ? "VARCHAR(255)" : col.getType());
            if (col.isPrimaryKey()) {
                sb.append(" NOT NULL");
                pk.add(quote(col.getName()));
            } else {
                sb.append(col.isNullable() ? " NULL" : " NOT NULL");
            }
            if (col.isAutoIncrement()) {
                sb.append(" AUTO_INCREMENT");
            }
            if (col.getDefaultValue() != null && !col.getDefaultValue().isBlank()) {
                sb.append(" DEFAULT ").append(col.getDefaultValue());
            }
            if (col.getComment() != null && !col.getComment().isBlank()) {
                sb.append(" COMMENT '").append(col.getComment().replace("'", "''")).append("'");
            }
            if (i < columns.size() - 1 || !pk.isEmpty()) {
                sb.append(",\n");
            }
        }
        if (!pk.isEmpty()) {
            sb.append("  PRIMARY KEY (").append(String.join(", ", pk)).append(")");
        } else if (sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 2);
        }
        return sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci").toString();
    }

    /**
     * 反引号包裹标识符，内部反引号双写转义。
     *
     * @param identifier 标识符原始值
     * @return 字符串
     */
    private static String quote(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("DDL 标识符不能为空");
        }
        return "`" + identifier.replace("`", "``") + "`";
    }
}
