package com.chua.common.support.lang.datasource.table;

import java.util.ArrayList;
import java.util.List;

/**
 * 链式 DDL 构建器，用于通过流式 API 生成 CREATE TABLE / ALTER TABLE / DROP TABLE 语句。
 * <p>
 * 支持三种构建模式，通过静态工厂方法创建：
 * <ul>
 *   <li>{@link #create(String)} — 生成 CREATE TABLE 语句</li>
 *   <li>{@link #alter(String)} — 生成 ALTER TABLE 语句</li>
 *   <li>{@link #drop(String)} — 生成 DROP TABLE 语句</li>
 * </ul>
 * </p>
 * <p>
 * 最终的 SQL 字符串通过 {@link #build(TableDdlRenderer)} 方法生成，
 * 需要传入一个实现了 {@link TableDdlRenderer} 接口的方言渲染器。
 * 渲染器通常由具体的数据库方言提供，以便生成符合该数据库语法的 DDL。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * Dialect dialect = Dialect.getExtension("mysql");
 *
 * // 创建表
 * String createSql = DdlBuilder.create("user")
 *     .column("id", "BIGINT").primaryKey().autoIncrement().columnComment("主键")
 *     .column("name", "VARCHAR(100)").notNull().columnComment("用户名")
 *     .column("age", "INT").defaultValue("0").columnComment("年龄")
 *     .column("email", "VARCHAR(200)").columnComment("邮箱")
 *     .comment("用户表")
 *     .engine("InnoDB")
 *     .charset("utf8mb4")
 *     .build(dialect::renderCreate);
 *
 * // 修改表（新增列）
 * String alterSql = DdlBuilder.alter("user")
 *     .column("phone", "VARCHAR(20)").after("email")
 *     .build(dialect::renderAlter);
 *
 * // 删除表
 * String dropSql = DdlBuilder.drop("user")
 *     .build(dialect::renderDrop);
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
public class DdlBuilder {

    /**
     * DDL 操作模式枚举。
     */
    public enum Mode {
        /** 建表模式 */
        CREATE,
        /** 改表模式 */
        ALTER,
        /** 删表模式 */
        DROP
    }

    /** 模式 */
    private final Mode mode;
    /** 表名称 */
    private final String tableName;
    /**
     * Schema 名
     */
    private String schema;
    /** Comment */
    private String comment;
    /** 引擎 */
    private String engine;
    /**
     * 字符集
     */
    private String charset;
    /** Columns */
    private final List<ColumnDef> columns = new ArrayList<>();
    /** Primarykeys */
    private final List<String> primaryKeys = new ArrayList<>();
    /** NEW表名称 */
    private String newTableName;

    /**
     * 创建 DdlBuilder 实例
     * @param mode mode
     * @param String String
     * @param tableName 表名称，不允许为 null
     */
    private DdlBuilder(Mode mode, String tableName) {
        this.mode = mode;
        this.tableName = tableName;
    }

    /**
     * 创建建表模式的构建器。
     *
     * @param tableName 表名
     * @return 构建器实例
     */
    public static DdlBuilder create(String tableName) {
        return new DdlBuilder(Mode.CREATE, tableName);
    }

    /**
     * 创建改表模式的构建器。
     *
     * @param tableName 表名
     * @return 构建器实例
     */
    public static DdlBuilder alter(String tableName) {
        return new DdlBuilder(Mode.ALTER, tableName);
    }

    /**
     * 创建删表模式的构建器。
     *
     * @param tableName 表名
     * @return 构建器实例
     */
    public static DdlBuilder drop(String tableName) {
        return new DdlBuilder(Mode.DROP, tableName);
    }

    /**
     * 设置数据库模式名（Schema）。
     *
     * @param schema 数据库模式名，为空时生成的 DDL 不带模式前缀
     * @return 当前构建器实例，便于链式调用
     */
    public DdlBuilder schema(String schema) {
        this.schema = schema;
        return this;
    }

    /**
     * 设置表注释。
     *
     * @param comment 表注释内容，为空时不生成 COMMENT 子句
     * @return 当前构建器实例，便于链式调用
     */
    public DdlBuilder comment(String comment) {
        this.comment = comment;
        return this;
    }

    /**
     * 设置数据库引擎（如 InnoDB）。
     *
     * @param engine 存储引擎名称，为空时不生成 ENGINE 子句
     * @return 当前构建器实例，便于链式调用
     */
    public DdlBuilder engine(String engine) {
        this.engine = engine;
        return this;
    }

    /**
     * 设置字符集（如 utf8mb4）。
     *
     * @param charset 字符集名称，为空时不生成 CHARSET 子句
     * @return 当前构建器实例，便于链式调用
     */
    public DdlBuilder charset(String charset) {
        this.charset = charset;
        return this;
    }

    /**
     * 设置重命名后的新表名（仅 ALTER 模式）。
     *
     * @param newName 重命名后的新表名，仅在 ALTER 模式下生效
     * @return 当前构建器实例，便于链式调用
     */
    public DdlBuilder renameTo(String newName) {
        this.newTableName = newName;
        return this;
    }

    /**
     * 添加列定义。
     *
     * @param name 列名
     * @param type 数据库类型字符串
     * @return this
     */
    public DdlBuilder column(String name, String type) {
        columns.add(new ColumnDef().setName(name).setType(type));
        return this;
    }

    /**
     * 添加完整的列定义对象。
     *
     * @param def 列定义
     * @return this
     */
    public DdlBuilder column(ColumnDef def) {
        columns.add(def);
        return this;
    }

    /**
     * 设置联合主键（指定多个列名）。
     *
     * @param keys 主键列名
     * @return this
     */
    public DdlBuilder primaryKey(String... keys) {
        primaryKeys.addAll(List.of(keys));
        return this;
    }

    /**
     * 将最后添加的列设为 NOT NULL。
     *
     * @return this
     */
    public DdlBuilder notNull() {
        if (!columns.isEmpty()) {
            columns.get(columns.size() - 1).setNullable(false);
        }
        return this;
    }

    /**
     * 将最后添加的列设为主键。
     * <p>同时自动设置该列为 NOT NULL。</p>
     *
     * @return this
     */
    public DdlBuilder primaryKey() {
        if (!columns.isEmpty()) {
            ColumnDef c = columns.get(columns.size() - 1);
            c.setPrimaryKey(true);
            c.setNullable(false);
        }
        return this;
    }

    /**
     * 将最后添加的列设为自增。
     *
     * @return this
     */
    public DdlBuilder autoIncrement() {
        if (!columns.isEmpty()) {
            columns.get(columns.size() - 1).setAutoIncrement(true);
        }
        return this;
    }

    /**
     * 将最后添加的列设为无符号（仅数值类型）。
     *
     * @return this
     */
    public DdlBuilder unsigned() {
        if (!columns.isEmpty()) {
            columns.get(columns.size() - 1).setUnsigned(true);
        }
        return this;
    }

    /**
     * 设置最后添加的列的默认值。
     *
     * @param val 默认值表达式
     * @return this
     */
    public DdlBuilder defaultValue(String val) {
        if (!columns.isEmpty()) {
            columns.get(columns.size() - 1).setDefaultValue(val);
        }
        return this;
    }

    /**
     * 设置最后添加的列的注释。
     *
     * @param val 列注释
     * @return this
     */
    public DdlBuilder columnComment(String val) {
        if (!columns.isEmpty()) {
            columns.get(columns.size() - 1).setComment(val);
        }
        return this;
    }

    /**
     * 将最后添加的列设为排在指定列之后（仅 ALTER 模式，MySQL 支持）。
     *
     * @param columnName 前一列名
     * @return this
     */
    public DdlBuilder after(String columnName) {
        if (!columns.isEmpty()) {
            columns.get(columns.size() - 1).setAfter(columnName);
        }
        return this;
    }

    /**
     * 将最后添加的列设为第一列（仅 ALTER 模式，MySQL 支持）。
     *
     * @return this
     */
    public DdlBuilder first() {
        if (!columns.isEmpty()) {
            columns.get(columns.size() - 1).setFirst(true);
        }
        return this;
    }

    /**
     * 构建 DDL SQL 语句。
     * <p>根据当前模式（CREATE / ALTER / DROP）调用渲染器的对应方法生成 SQL。</p>
     *
     * @param renderer 方言渲染器，负责将 {@link TableDef} 转换为特定数据库的 DDL 语句
     * @return DDL SQL 字符串
     */
    public String build(TableDdlRenderer renderer) {
        TableDef def = new TableDef()
                .setName(tableName)
                .setSchema(schema)
                .setComment(comment)
                .setEngine(engine)
                .setCharset(charset)
                .setColumns(columns);
        if (!primaryKeys.isEmpty()) {
            def.setPrimaryKeys(primaryKeys.toArray(new String[0]));
        }
        return switch (mode) {
            case CREATE -> renderer.renderCreate(def);
            case ALTER -> renderer.renderAlter(def, newTableName);
            case DROP -> renderer.renderDrop(tableName);
        };
    }

    /**
     * DDL 渲染器函数式接口。
     * <p>由具体的数据库方言实现，将结构化的表定义转换为特定数据库语法的 DDL 语句。</p>
     */
    @FunctionalInterface
    public interface TableDdlRenderer {

        /**
         * 渲染 CREATE TABLE 语句。
         *
         * @param def 表定义
         * @return CREATE TABLE SQL
         */
        String renderCreate(TableDef def);

        /**
         * 渲染 ALTER TABLE 语句。
         * <p>默认抛出 {@link UnsupportedOperationException}，由方言按需覆盖。</p>
         *
         * @param def     表定义
         * @param newName 重命名后的新表名（可为 null）
         * @return ALTER TABLE SQL
         */
        default String renderAlter(TableDef def, String newName) {
            throw new UnsupportedOperationException("ALTER TABLE 操作未实现");
        }

        /**
         * 渲染 DROP TABLE 语句。
         *
         * @param tableName 表名
         * @return DROP TABLE SQL
         */
        default String renderDrop(String tableName) {
            return "drop table if exists " + tableName;
        }
    }
}
