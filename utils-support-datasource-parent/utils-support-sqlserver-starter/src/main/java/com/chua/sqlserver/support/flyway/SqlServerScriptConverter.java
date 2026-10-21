package com.chua.sqlserver.support.flyway;

import com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter;
import com.chua.common.support.spi.annotations.Spi;

import java.util.regex.Pattern;

/**
 * SQL Server 迁移脚本方言转换器（{@code ScriptConverter} 的 sqlserver 协议实现）。
 *
 * <p>SQL Server 此前没有 ScriptConverter 实现，落到兜底的 {@link DefaultScriptConverter}，
 * 而兜底实现只做了少量类型/函数映射，缺了 SQL Server 的硬性约束。</p>
 *
 * <p>本类补齐的差异（依据 SQL Server 官方文档；标识符定界与自增写法另经
 * H2 的 {@code MODE=MSSQLServer} 兼容模式实测确认）：</p>
 * <ul>
 *   <li><b>标识符定界</b>：SQL Server 支持方括号 {@code [name]} 与双引号，
 *       不认反引号。父类的 {@code NO_BACKTICK_PROTOCOLS} 已含本协议，
 *       由父类把反引号统一改写成双引号（SQL Server 开启
 *       {@code QUOTED_IDENTIFIER} 后双引号即定界符，默认开启）</li>
 *   <li><b>文本类型</b>：{@code TEXT}/{@code LONGTEXT}/{@code MEDIUMTEXT}/{@code TINYTEXT}
 *       在 SQL Server 里都是已废弃的 {@code TEXT}（且不支持比较、不能建索引），
 *       统一改写成 {@code VARCHAR(MAX)}</li>
 *   <li><b>数值类型</b>：{@code TINYINT}/{@code MEDIUMINT} 在 SQL Server 里不存在
 *       （{@code TINYINT} 是 {@code BIT} 的别名，不是数值），统一用 {@code INT}</li>
 *   <li><b>布尔类型</b>：{@code BOOLEAN}/{@code BOOL} → SQL Server 的 {@code BIT}</li>
 *   <li><b>二进制类型</b>：{@code BLOB}/{@code VARBINARY}/{@code BINARY} →
 *       SQL Server 的 {@code VARBINARY(MAX)}</li>
 *   <li><b>时间类型</b>：{@code DATETIME} → {@code DATETIME2}（{@code DATETIME}
 *       的精度只有毫秒且范围窄）</li>
 *   <li><b>函数</b>：{@code NOW()} → {@code GETDATE()}、{@code IFNULL(a,b)} → {@code ISNULL(a,b)}</li>
 *   <li><b>列级自增</b>：父类已改写成 {@code IDENTITY(1,1)}（SQL Server 原生支持）</li>
 *   <li><b>{@code INSERT IGNORE}</b>：SQL Server 没有「忽略冲突」的修饰写法，整条跳过
 *       （等价写法需要 {@code MERGE} 或「先查后插」，需要表结构元数据）</li>
 *   <li><b>{@code ON DUPLICATE KEY UPDATE}</b>：SQL Server 的等价写法是 {@code MERGE}，
 *       需要表结构元数据，整条跳过</li>
 * </ul>
 *
 * <p>扩展方式：扩展键必须等于协议名（{@code @Spi("sqlserver")} + 本模块
 * {@code META-INF/extensions/...ScriptConverter} 中的 {@code sqlserver=} 行），
 * 否则会与兜底默认实现同键互相遮蔽。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = SqlServerScriptConverter.PROTOCOL, order = 100)
public class SqlServerScriptConverter extends DefaultScriptConverter {

    /**
     * 本协议名（同时作为 SPI 扩展键）
     */
    public static final String PROTOCOL = "sqlserver";

    /**
     * {@code INSERT IGNORE INTO} — 整条跳过
     */
    private static final Pattern INSERT_IGNORE = Pattern.compile(
            "(?i)^\\s*INSERT\\s+IGNORE\\b");

    /**
     * {@code ON DUPLICATE KEY UPDATE} — 整条跳过
     */
    private static final Pattern ON_DUPLICATE_KEY_UPDATE = Pattern.compile(
            "(?i)\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b");

    /**
     * {@code TINYINT} → {@code INT}。
     *
     * <p>SQL Server 没有 {@code TINYINT} 数值类型（{@code TINYINT} 是 {@code BIT}
     * 的同义词，存 0/1，不是 1 字节整数）；{@code MEDIUMINT} 同样不存在。
     * 统一用 4 字节 {@code INT}，避免溢出。</p>
     */
    private static final Pattern TINYINT_TYPE = Pattern.compile(
            "(?i)\\bTINYINT\\b|\\bMEDIUMINT\\b");

    /**
     * {@code BOOLEAN} / {@code BOOL} → SQL Server 的 {@code BIT}。
     */
    private static final Pattern BOOLEAN_TYPE = Pattern.compile("(?i)\\bBOOLEAN\\b|\\bBOOL\\b");

    /**
     * {@code YEAR} → {@code SMALLINT}。
     *
     * <p>SQL Server 没有 {@code YEAR} 类型。</p>
     */
    private static final Pattern YEAR_TYPE = Pattern.compile("(?i)\\bYEAR\\b");

    /**
     * {@code BLOB} / {@code VARBINARY(n)} / {@code BINARY(n)} → {@code VARBINARY(MAX)}。
     */
    private static final Pattern BINARY_TYPE = Pattern.compile(
            "(?i)\\b(?:VAR)?BINARY\\s*\\(\\s*\\d+\\s*\\)|\\bBLOB\\b");

    @Override
    public boolean supports(String protocol) {
        return PROTOCOL.equalsIgnoreCase(protocol);
    }

    @Override
    public String convert(String statement, String protocol) {
        // 先走父类：剥离表尾 MySQL 属性、行内 KEY/UNIQUE KEY、列内联 COMMENT、列级 CHARSET/COLLATE、
        // 标识符定界改写，以及 TEXT/DATETIME/IFNULL() 的基础映射
        String converted = super.convert(statement, protocol);
        if (converted == null || converted.isBlank()) {
            return converted;
        }
        // SQL Server 没有「忽略冲突」与 upsert 的 MySQL 语法，整条跳过
        if (INSERT_IGNORE.matcher(converted).find()) {
            return null;
        }
        if (ON_DUPLICATE_KEY_UPDATE.matcher(converted).find()) {
            return null;
        }
        // 类型映射
        converted = TINYINT_TYPE.matcher(converted).replaceAll("INT");
        converted = BOOLEAN_TYPE.matcher(converted).replaceAll("BIT");
        converted = YEAR_TYPE.matcher(converted).replaceAll("SMALLINT");
        converted = BINARY_TYPE.matcher(converted).replaceAll("VARBINARY(MAX)");
        // 表别名补 AS：SQL Server 把「表名 别名」解析成「schema.表」
        // （实测报 Schema "X" not found），加 AS 才是表的别名
        converted = TABLE_ALIAS.matcher(converted).replaceAll("$1 AS $2");
        return converted;
    }

    /**
     * {@code FROM 表名 别名} 里的表别名（补 AS）
     *
     * <p>捕获组 1=FROM/JOIN 与空白，2=别名。
     * 不补 AS 的话，SQL Server 会把别名当成 schema 名
     * （实测报 {@code Schema "X" not found}）。</p>
     *
     * <p>别名排除 SQL 关键字：{@code FROM t WHERE ...} 里的 WHERE 是关键字、
     * 不是别名，误加 AS 会变成 {@code FROM t AS WHERE}。</p>
     */
    private static final Pattern TABLE_ALIAS = Pattern.compile(
            "(?i)(\\b(?:FROM|JOIN)\\s+[\"`]?[A-Za-z_]\\w*[\"`]?\\s+)"
                    + "(?!AS\\b|WHERE\\b|SELECT\\b|AND\\b|OR\\b|ON\\b|JOIN\\b|LEFT\\b|RIGHT\\b|INNER\\b"
                    + "|OUTER\\b|CROSS\\b|GROUP\\b|ORDER\\b|HAVING\\b|LIMIT\\b|UNION\\b|VALUES\\b"
                    + "|SET\\b|USING\\b|WHEN\\b|THEN\\b|ELSE\\b|END\\b|CASE\\b)([A-Za-z_]\\w*)");

}
