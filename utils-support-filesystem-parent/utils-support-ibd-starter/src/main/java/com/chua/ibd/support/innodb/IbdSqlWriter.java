package com.chua.ibd.support.innodb;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 由 SDI 表定义 + 行数据生成可执行 SQL 脚本（建表 DDL + INSERT）。
 *
 * <p>这是纯 Java 解析路线自带的 SQL 生成器：结构信息全部来自 SDI，因此不需要
 * 连 MySQL、也不需要 {@code SHOW CREATE TABLE}。</p>
 *
 * <h3>生成口径</h3>
 * <ul>
 *   <li>列类型直接用 SDI 的 {@code column_type_utf8}（如 {@code smallint unsigned}、
 *       {@code enum('G','PG')}），它本来就是 MySQL 自己的写法，最不容易走样；</li>
 *   <li>{@code ENUM} / {@code SET} 的候选值在 SDI 里是 base64，已在
 *       {@link IbdSdiReader} 里还原；</li>
 *   <li>建表语句用 {@code IF NOT EXISTS}，重复执行安全；</li>
 *   <li><b>不生成外键约束</b>：单表还原时被引用的表未必存在，带上外键会让脚本直接失败。
 *       需要外键的场合建议导入后用 {@code ALTER TABLE ... ADD CONSTRAINT} 单独补。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IbdSqlWriter {

    /**
     * 工具类，禁止实例化。
     */
    private IbdSqlWriter() {
    }

    /**
     * 生成建表语句。
     *
     * @param definition 表定义
     * @param schema     目标库名；为空表示不加库名限定
     * @param table      目标表名；为空表示沿用原名
     * @return 建表 SQL（以分号结尾，不含换行结尾）
     */
    public static String createTable(IbdTableDefinition definition, String schema, String table) {
        String target = table == null || table.isBlank() ? definition.name() : table;
        StringBuilder sql = new StringBuilder(512);
        sql.append("CREATE TABLE IF NOT EXISTS ").append(qualified(schema, target)).append(" (\n");

        List<String> parts = new java.util.ArrayList<>();
        for (IbdColumn column : definition.userColumns()) {
            parts.add("  " + columnDefinition(column, definition));
        }
        for (IbdIndex index : definition.indexes()) {
            String key = keyDefinition(index);
            if (key != null) {
                parts.add("  " + key);
            }
        }
        sql.append(String.join(",\n", parts));
        sql.append("\n) ENGINE=InnoDB DEFAULT CHARSET=").append(definition.charsetName());
        String rowFormat = definition.rowFormat();
        if (rowFormat != null && !rowFormat.isEmpty() && !"DYNAMIC".equals(rowFormat)) {
            sql.append(" ROW_FORMAT=").append(rowFormat);
        }
        sql.append(';');
        return sql.toString();
    }

    /**
     * 生成单列的列定义片段。
     *
     * @param column     列
     * @param definition 表定义（取表默认字符集用于判断是否需要显式声明列字符集）
     * @return 列定义片段
     */
    private static String columnDefinition(IbdColumn column, IbdTableDefinition definition) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(quote(column.name())).append(' ').append(column.typeText());
        if (isTextual(column) && !column.charsetName().equals(definition.charsetName())) {
            sb.append(" CHARACTER SET ").append(column.charsetName());
        }
        sb.append(column.nullable() ? " NULL" : " NOT NULL");
        String defaultClause = defaultClause(column);
        if (!defaultClause.isEmpty()) {
            sb.append(' ').append(defaultClause);
        }
        if (column.autoIncrement()) {
            sb.append(" AUTO_INCREMENT");
        }
        if (column.comment() != null && !column.comment().isEmpty()) {
            sb.append(" COMMENT ").append(stringLiteral(column.comment()));
        }
        return sb.toString();
    }

    /**
     * 生成列的默认值片段。
     *
     * @param column 列
     * @return 片段；无需默认值时返回空串
     */
    private static String defaultClause(IbdColumn column) {
        if (!column.hasDefault()) {
            return column.nullable() ? "DEFAULT NULL" : "";
        }
        String value = column.defaultValue();
        if (value == null || value.isEmpty()) {
            return column.nullable() ? "DEFAULT NULL" : "";
        }
        if (isExpression(value)) {
            return "DEFAULT " + value;
        }
        if (isNumeric(column)) {
            return "DEFAULT " + value;
        }
        return "DEFAULT " + stringLiteral(value);
    }

    /**
     * 判断默认值是否为 SQL 表达式（如 {@code CURRENT_TIMESTAMP}），而不是字面量。
     *
     * @param value 默认值文本
     * @return 是表达式返回 true
     */
    private static boolean isExpression(String value) {
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("CURRENT_TIMESTAMP") || upper.startsWith("(") || upper.contains("()");
    }

    /**
     * 判断列是否为数值/位类型（这类默认值不加引号）。
     *
     * @param column 列
     * @return 是返回 true
     */
    private static boolean isNumeric(IbdColumn column) {
        switch (column.type()) {
            case TINY:
            case SHORT:
            case LONG:
            case LONGLONG:
            case INT24:
            case FLOAT:
            case DOUBLE:
            case NEWDECIMAL:
            case DECIMAL:
            case BIT:
            case YEAR:
                return true;
            default:
                return false;
        }
    }

    /**
     * 判断列是否为文本/二进制类（需要声明字符集）。
     *
     * @param column 列
     * @return 是返回 true
     */
    private static boolean isTextual(IbdColumn column) {
        switch (column.type()) {
            case VARCHAR:
            case VAR_STRING:
            case STRING:
            case TINY_BLOB:
            case MEDIUM_BLOB:
            case LONG_BLOB:
            case BLOB:
                return true;
            default:
                return false;
        }
    }

    /**
     * 生成索引定义片段。
     *
     * @param index 索引
     * @return 片段；该索引不该出现在 DDL 里时返回 {@code null}
     */
    private static String keyDefinition(IbdIndex index) {
        if (index.hidden() && !index.primary()) {
            return null;
        }
        StringBuilder columns = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (IbdColumn column : index.columns()) {
            if (column.systemColumn() || !seen.add(column.name())) {
                continue;
            }
            if (columns.length() > 0) {
                columns.append(',');
            }
            columns.append(quote(column.name())).append(prefixOf(index, column));
        }
        if (columns.length() == 0) {
            return null;
        }
        if (index.primary()) {
            return "PRIMARY KEY (" + columns + ")";
        }
        String keyword = index.type() == IbdIndex.TYPE_UNIQUE ? "UNIQUE KEY " : "KEY ";
        return keyword + quote(index.name()) + " (" + columns + ")";
    }

    /**
     * 生成索引列的前缀长度（仅大对象类型需要，其余返回空串）。
     *
     * @param index  索引
     * @param column 列
     * @return 形如 {@code (191)}；不需要前缀时返回空串
     */
    private static String prefixOf(IbdIndex index, IbdColumn column) {
        switch (column.type()) {
            case TINY_BLOB:
            case MEDIUM_BLOB:
            case LONG_BLOB:
            case BLOB:
                break;
            default:
                return "";
        }
        int bytesPerChar = bytesPerChar(column.charsetName());
        long maxBytes = column.charLength() > 0 ? column.charLength() : 255L;
        long chars = Math.max(1, (maxBytes + bytesPerChar - 1) / bytesPerChar);
        return "(" + chars + ")";
    }

    /**
     * 按字符集名估计单字符最大字节数。
     *
     * @param charsetName 字符集名
     * @return 字节数
     */
    private static int bytesPerChar(String charsetName) {
        switch (charsetName) {
            case "utf8mb4":
                return 4;
            case "utf8mb3":
            case "utf8":
                return 3;
            case "ucs2":
            case "utf16":
            case "utf16le":
                return 2;
            default:
                return 1;
        }
    }

    /**
     * 生成 {@code INSERT INTO ... VALUES (...);} 语句。
     *
     * @param definition 表定义（取列顺序）
     * @param rows       行数据（列名 → 值）
     * @param schema     目标库名；为空表示不加库名限定
     * @param table      目标表名；为空表示沿用原名
     * @param columns    输出列名（通常等于 {@code definition.userColumns()} 的名字）
     * @return INSERT 语句列表
     */
    public static java.util.List<String> insertStatements(IbdTableDefinition definition,
                                                         List<Map<String, Object>> rows,
                                                         String schema, String table,
                                                         List<String> columns) {
        String target = table == null || table.isBlank() ? definition.name() : table;
        String prefix = "INSERT INTO " + qualified(schema, target) + " ("
                + joinQuoted(columns) + ") VALUES (";
        java.util.List<String> statements = new java.util.ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            StringBuilder sb = new StringBuilder(prefix.length() + columns.size() * 12);
            sb.append(prefix);
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(literal(row.get(columns.get(i))));
            }
            sb.append(");");
            statements.add(sb.toString());
        }
        return statements;
    }

    /**
     * 把值转成 SQL 字面量。
     *
     * @param value 值
     * @return 字面量文本
     */
    public static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String text = String.valueOf(value);
        if (text.startsWith("0x") && text.length() > 2 && isHex(text.substring(2))) {
            return text;
        }
        return stringLiteral(text);
    }

    /**
     * 判断字符串是否为纯十六进制。
     *
     * @param text 文本
     * @return 是返回 true
     */
    private static boolean isHex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * 转义并加单引号。
     *
     * @param text 原始文本
     * @return SQL 字符串字面量
     */
    public static String stringLiteral(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        sb.append('\'');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\'':
                    sb.append("''");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\0':
                    sb.append("\\0");
                    break;
                case '\u001a':
                    sb.append("\\Z");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.append('\'').toString();
    }

    /**
     * 拼「库名.表名」，库名为空时只给表名。
     *
     * @param schema 库名
     * @param table  表名
     * @return 限定名
     */
    public static String qualified(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
    }

    /**
     * 给标识符加反引号，并把内部的反引号翻倍。
     *
     * @param name 标识符
     * @return 加引号后的标识符
     */
    public static String quote(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    /**
     * 拼接并加引号的列名列表。
     *
     * @param columns 列名
     * @return 形如 {@code `a`,`b`}
     */
    private static String joinQuoted(List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (String column : columns) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(quote(column));
        }
        return sb.toString();
    }
}
