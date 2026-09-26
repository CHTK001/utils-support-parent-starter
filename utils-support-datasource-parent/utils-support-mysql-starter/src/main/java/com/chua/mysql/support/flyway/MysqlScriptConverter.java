package com.chua.mysql.support.flyway;

import com.chua.common.support.lang.datasource.dialect.SqlName;
import com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter;
import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 系迁移脚本方言转换器（{@code ScriptConverter} 的 mysql 协议实现）。
 *
 * <p>背景：项目脚本统一以 MySQL 风格书写，但 MySQL 存在一批「其它方言支持、MySQL 不支持」的
 * 幂等 DDL 写法，最典型的是 {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}：
 * MySQL 的 {@code ADD COLUMN} 不接受 {@code IF NOT EXISTS}，直接执行必然语法错误。
 * 这些语句在本项目中又与 {@code CREATE TABLE} 全量定义重复，属于纯冗余。</p>
 *
 * <p>本转换器的处理策略是「改写为 MySQL 原生可执行的元数据守卫动态 SQL」，而不是简单跳过：
 * 跳过虽然不报错，但会让依赖该语句补列的存量库静默缺列；改写后语句在 MySQL 上
 * 真实可执行，且列已存在时自动变成空操作，仍然幂等。</p>
 *
 * <h3>展开规则</h3>
 * <ul>
 *   <li>{@code ALTER TABLE t ADD COLUMN IF NOT EXISTS c ...} → 5 条语句：
 *       {@code SET @var := (...information_schema 查询...)}、
 *       {@code SET @ddl := IF(@var = 0, 'ALTER TABLE ... ADD COLUMN ...', 'DO 0')}、
 *       {@code PREPARE}、{@code EXECUTE}、{@code DEALLOCATE PREPARE}</li>
 *   <li>{@code CREATE INDEX IF NOT EXISTS ...} / {@code DROP INDEX IF EXISTS ...} 同样守卫展开</li>
 *   <li>表名、列名、索引名先经 {@link SqlName#checkSimple} 白名单校验，
 *       拒绝引号、空白与控制字符，保证拼进动态 SQL 字符串字面量时不会改变语句结构</li>
 *   <li>其余语句原样透传：MySQL 是本项目脚本的源方言，无需剥离</li>
 * </ul>
 *
 * <p>之所以覆写 {@code convertAll} 而不是 {@code convert}：单条语句展开成多条语句
 * 超出了 {@code convert} 的「一进一出」契约，而 {@code convertAll} 是接口默认方法，
 * 覆写它可保持向后兼容，且两个调用方（{@code DataSourceFlyway}、
 * {@code DefaultFlyway}）本来就统一走 {@code convertAll}。</p>
 *
 * <p>扩展键必须等于协议名（{@code @Spi("mysql")} + 本模块
 * {@code META-INF/extensions/...ScriptConverter} 中的 {@code mysql=} 行），
 * 否则会与兜底默认实现同键互相遮蔽。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = MysqlScriptConverter.PROTOCOL, order = 100)
public class MysqlScriptConverter extends DefaultScriptConverter {

    /**
     * 本协议名（同时作为 SPI 扩展键）
     */
    public static final String PROTOCOL = "mysql";

    /**
     * MySQL 系协议集合：这些协议共享同一套「不支持 ADD COLUMN IF NOT EXISTS」的方言限制
     */
    private static final Set<String> MYSQL_FAMILY = Set.of("mysql", "mariadb", "tidb", "oceanbase");

    /**
     * {@code ALTER TABLE t ADD COLUMN [IF NOT EXISTS] c <定义>}
     * 捕获表名、是否带 IF NOT EXISTS、列名与列定义
     */
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+`?([A-Za-z_]\\w*)`?\\s+ADD\\s+COLUMN\\s+(IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "`?([A-Za-z_]\\w*)`?\\s+(.+?)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * {@code CREATE [UNIQUE] INDEX [IF NOT EXISTS] idx ON tbl (cols)}
     */
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "`?([A-Za-z_]\\w*)`?\\s+ON\\s+`?([A-Za-z_]\\w*)`?\\s*\\((.+?)\\)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * {@code DROP INDEX [IF EXISTS] idx ON tbl}
     */
    private static final Pattern DROP_INDEX = Pattern.compile(
            "^\\s*DROP\\s+INDEX\\s+(IF\\s+EXISTS\\s+)?`?([A-Za-z_]\\w*)`?\\s+ON\\s+`?([A-Za-z_]\\w*)`?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public boolean supports(String protocol) {
        return protocol != null && MYSQL_FAMILY.contains(protocol.toLowerCase(Locale.ROOT));
    }

    @Override
    public List<String> convertAll(List<String> statements, String protocol) {
        List<String> result = new ArrayList<>();
        if (statements == null || statements.isEmpty()) {
            return result;
        }
        for (String statement : statements) {
            if (statement == null || statement.isBlank()) {
                continue;
            }
            if (!expandGuardedDdl(statement, result)) {
                String converted = super.convert(statement, protocol);
                if (converted != null && !converted.isBlank()) {
                    result.add(converted);
                }
            }
        }
        return result;
    }

    /**
     * 尝试把「MySQL 不支持的幂等 DDL」展开为 information_schema 守卫的动态 SQL。
     *
     * @param statement 原始语句
     * @param out        展开结果输出
     * @return true 表示已展开（无需再走默认转换）
     */
    private boolean expandGuardedDdl(String statement, List<String> out) {
        String upper = statement.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("ALTER TABLE")) {
            return expandAddColumn(statement, out);
        }
        if (upper.startsWith("CREATE INDEX") || upper.startsWith("CREATE UNIQUE INDEX")) {
            return expandCreateIndex(statement, out);
        }
        if (upper.startsWith("DROP INDEX")) {
            return expandDropIndex(statement, out);
        }
        return false;
    }

    /**
     * 展开 {@code ADD COLUMN IF NOT EXISTS}。
     *
     * @param statement 原始语句
     * @param out        展开结果输出
     * @return true 表示已展开
     */
    private boolean expandAddColumn(String statement, List<String> out) {
        Matcher matcher = ADD_COLUMN.matcher(statement);
        if (!matcher.matches() || matcher.group(2) == null) {
            return false;
        }
        String table = SqlName.checkSimple(matcher.group(1), "表名");
        String column = SqlName.checkSimple(matcher.group(3), "列名");
        String definition = matcher.group(4).trim();
        // 列定义里若含注释残留的引号，直接跳过展开，交由默认转换处理
        if (definition.indexOf('\'') >= 0) {
            return false;
        }
        String quotedTable = SqlName.quoteMysql(table, "表名");
        String quotedColumn = SqlName.quoteMysql(column, "列名");
        String alter = "ALTER TABLE " + quotedTable + " ADD COLUMN " + quotedColumn + " " + definition;
        out.add("SET @ddl_col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND COLUMN_NAME = '" + column + "')");
        out.add("SET @ddl_col_sql := IF(@ddl_col_exists = 0, '" + escapeLiteral(alter) + "', 'DO 0')");
        out.add("PREPARE ddl_col_stmt FROM @ddl_col_sql");
        out.add("EXECUTE ddl_col_stmt");
        out.add("DEALLOCATE PREPARE ddl_col_stmt");
        return true;
    }

    /**
     * 展开 {@code CREATE INDEX IF NOT EXISTS}。
     *
     * @param statement 原始语句
     * @param out        展开结果输出
     * @return true 表示已展开
     */
    private boolean expandCreateIndex(String statement, List<String> out) {
        Matcher matcher = CREATE_INDEX.matcher(statement);
        if (!matcher.matches() || matcher.group(3) == null) {
            return false;
        }
        String unique = matcher.group(1) == null ? "" : "UNIQUE ";
        String index = SqlName.checkSimple(matcher.group(3), "索引名");
        String table = SqlName.checkSimple(matcher.group(4), "表名");
        String columns = matcher.group(5).trim();
        if (columns.indexOf('\'') >= 0) {
            return false;
        }
        String create = "CREATE " + unique + "INDEX " + SqlName.quoteMysql(index, "索引名")
                + " ON " + SqlName.quoteMysql(table, "表名") + " (" + columns + ")";
        out.add("SET @ddl_idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND INDEX_NAME = '" + index + "')");
        out.add("SET @ddl_idx_sql := IF(@ddl_idx_exists = 0, '" + escapeLiteral(create) + "', 'DO 0')");
        out.add("PREPARE ddl_idx_stmt FROM @ddl_idx_sql");
        out.add("EXECUTE ddl_idx_stmt");
        out.add("DEALLOCATE PREPARE ddl_idx_stmt");
        return true;
    }

    /**
     * 展开 {@code DROP INDEX IF EXISTS}。
     *
     * @param statement 原始语句
     * @param out        展开结果输出
     * @return true 表示已展开
     */
    private boolean expandDropIndex(String statement, List<String> out) {
        Matcher matcher = DROP_INDEX.matcher(statement);
        if (!matcher.matches() || matcher.group(1) == null) {
            return false;
        }
        String index = SqlName.checkSimple(matcher.group(2), "索引名");
        String table = SqlName.checkSimple(matcher.group(3), "表名");
        String drop = "DROP INDEX " + SqlName.quoteMysql(index, "索引名")
                + " ON " + SqlName.quoteMysql(table, "表名");
        out.add("SET @ddl_idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND INDEX_NAME = '" + index + "')");
        out.add("SET @ddl_idx_sql := IF(@ddl_idx_exists > 0, '" + escapeLiteral(drop) + "', 'DO 0')");
        out.add("PREPARE ddl_idx_stmt FROM @ddl_idx_sql");
        out.add("EXECUTE ddl_idx_stmt");
        out.add("DEALLOCATE PREPARE ddl_idx_stmt");
        return true;
    }

    /**
     * 转义动态 SQL 字符串字面量中的单引号（MySQL 约定：单引号写两遍）。
     *
     * @param value 原始文本
     * @return 可安全嵌入单引号字面量的文本
     */
    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }
}
