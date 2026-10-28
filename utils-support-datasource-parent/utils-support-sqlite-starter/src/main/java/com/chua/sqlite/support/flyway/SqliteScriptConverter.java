package com.chua.sqlite.support.flyway;

import com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter;
import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLite 迁移脚本方言转换器（{@code ScriptConverter} 的 sqlite 协议实现）。
 *
 * <p>在 {@link DefaultScriptConverter} 的通用剥离规则（表尾 {@code ENGINE=}、行内 {@code KEY}、
 * 列内联 {@code COMMENT}、类型/函数映射）之上，补齐 SQLite 特有的三处硬性差异。以下行为均经
 * {@code sqlite-jdbc} 实测确认：</p>
 * <ul>
 *   <li>列级 {@code AUTO_INCREMENT} 不是 SQLite 语法；自增只能写成
 *       {@code <列名> INTEGER PRIMARY KEY AUTOINCREMENT}，且类型必须恰为 {@code INTEGER}
 *       （{@code BIGINT AUTO_INCREMENT} 直接语法错误）</li>
 *   <li>同一张表出现列级与表级两个主键声明会报
 *       {@code table has more than one primary key}，因此下推到列上时必须移除对应的表级
 *       {@code PRIMARY KEY (col)} 行；复合主键或与自增列不同名的主键不做下推（仅去掉关键字）</li>
 *   <li>{@code INSERT IGNORE} 语法错误，等价写法是 {@code INSERT OR IGNORE}</li>
 *   <li>{@code ON DUPLICATE KEY UPDATE c = VALUES(c)} 需改写为 upsert：
 *       {@code ON CONFLICT DO UPDATE SET c = excluded.c}。SQLite 的 {@code DO UPDATE}
 *       允许省略冲突目标并对任意唯一约束生效，因此无需预知主键列
 *       （显式写 {@code ON CONFLICT(col)} 反而要求该列有唯一约束，否则报
 *       {@code ON CONFLICT clause does not match any PRIMARY KEY or UNIQUE constraint}）；
 *       而 {@code VALUES(col)} 在 upsert 段内是语法错误，必须换成 {@code excluded.col}</li>
 * </ul>
 *
 * <p>扩展方式：扩展键必须等于协议名（{@code @Spi("sqlite")} + 本模块
 * {@code META-INF/extensions/...ScriptConverter} 中的 {@code sqlite=} 行），
 * 否则会与兜底默认实现同键互相遮蔽。</p>
 *
 * <p>前提与 {@link DefaultScriptConverter} 一致：{@code CREATE TABLE} 按每列一行的对齐格式书写；
 * 单行紧凑写法下自增列无法识别，退化为仅剥离 {@code AUTO_INCREMENT} 关键字（建表可执行，
 * 但列不再自动编号）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = SqliteScriptConverter.PROTOCOL, order = 100)
public class SqliteScriptConverter extends DefaultScriptConverter {

    /**
     * 本协议名（同时作为 SPI 扩展键）
     */
    public static final String PROTOCOL = "sqlite";

    /**
     * 自增列声明行（整行）：{@code `id` BIGINT NOT NULL AUTO_INCREMENT,}
     * 捕获列名与尾随逗号，中间的 MySQL 类型在重写时被 {@code INTEGER} 取代
     *
     * <p>允许 {@code AUTO_INCREMENT} 之后还有列内联注释
     * （{@code `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '成语ID',}）——
     * 不放行这一段会漏判，表现为「自增语义丢失」：列退化成普通列、
     * 插入时主键为 null，关联表的外键列随即报 NOT NULL 约束失败。</p>
     */
    private static final Pattern AUTO_INCREMENT_COLUMN_LINE = Pattern.compile(
            "^(\\s*)(`[^`]+`|[A-Za-z_]\\w*)(.*\\bAUTO_INCREMENT\\b"
                    + "(?:\\s+COMMENT\\s+'(?:[^']|'')*')?)(\\s*,?\\s*)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 表级主键声明行（任意形态，含复合主键），用于判断能否安全下推
     */
    private static final Pattern TABLE_PRIMARY_KEY_ANY = Pattern.compile(
            "^\\s*PRIMARY\\s+KEY\\s*\\(", Pattern.CASE_INSENSITIVE);

    /**
     * 表级单列主键声明行：{@code PRIMARY KEY (`id`)}（可带尾随逗号）
     */
    private static final Pattern TABLE_PRIMARY_KEY_LINE = Pattern.compile(
            "^\\s*PRIMARY\\s+KEY\\s*\\(\\s*(`[^`]+`|[A-Za-z_]\\w*)\\s*\\)\\s*,?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 行首的「缩进 + 列名」：{@code     `id` BIGINT ...}
     * 捕获组 1=缩进，2=列名（可能带反引号）
     */
    private static final Pattern LEADING_COLUMN = Pattern.compile(
            "^(\\s*)(`[^`]+`|[A-Za-z_]\\w*)");

    /**
     * 任意位置的 {@code AUTO_INCREMENT} 关键字（含 MySQL 的 {@code AUTO_INCREMENT=100} 表尾形式）
     */
    private static final Pattern AUTO_INCREMENT_TOKEN = Pattern.compile(
            "\\s*\\bAUTO_INCREMENT\\b(\\s*=\\s*\\d+)?", Pattern.CASE_INSENSITIVE);

    /**
     * {@code INSERT IGNORE}
     */
    private static final Pattern INSERT_IGNORE = Pattern.compile(
            "\\bINSERT\\s+IGNORE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code ON DUPLICATE KEY UPDATE}
     */
    private static final Pattern ON_DUPLICATE_KEY_UPDATE = Pattern.compile(
            "\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * upsert 段内的 MySQL 取值函数 {@code VALUES(col)}
     */
    private static final Pattern VALUES_FUNCTION = Pattern.compile(
            "\\bVALUES\\s*\\(\\s*(`[^`]+`|[A-Za-z_]\\w*)\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * {@code REPLACE(UUID(), '-', '')} → SQLite 的随机十六进制串。
     *
     * <p>SQLite 没有 {@code UUID()} 函数（实测报
     * {@code [SQLITE_ERROR] SQL error or missing database (no such function: UUID)}），
     * 也没有 {@code REPLACE} 之外能去掉连字符的必要 —— 种子数据里的 UUID 只是
     * 拿来当唯一标识的随机串，不要求是标准 UUID 格式。</p>
     *
     * <p>{@code lower(hex(randomblob(16)))} 是 SQLite 原生的等价写法：
     * {@code randomblob(16)} 产生 16 字节随机数，{@code hex} 转成 32 位十六进制，
     * {@code lower} 统一大小写 —— 与 {@code REPLACE(UUID(), '-', '')} 的
     * 32 位十六进制形态一致。</p>
     */
    private static final Pattern UUID_FUNCTION = Pattern.compile(
            "\\bREPLACE\\s*\\(\\s*UUID\\s*\\(\\s*\\)\\s*,\\s*'-'\\s*,\\s*''\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 裸 {@code UUID()}（不带 REPLACE）→ 同上
     */
    private static final Pattern BARE_UUID_FUNCTION = Pattern.compile(
            "\\bUUID\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * SQLite 的随机唯一标识写法
     */
    private static final String SQLITE_UUID = "lower(hex(randomblob(16)))";

    /**
     * SQLite 自增列写法
     */
    private static final String AUTO_INCREMENT_COLUMN = " INTEGER PRIMARY KEY AUTOINCREMENT";

    @Override
    public boolean supports(String protocol) {
        return PROTOCOL.equalsIgnoreCase(protocol);
    }

    /**
     * 批量转换：建表语句先把自增列下推成 SQLite 的写法，再交父类做通用剥离。
     *
     * <p><b>为什么必须在这里拦一道</b>：父类的 {@code convertAll} 对建表语句会先调
     * {@code convertCreateTable}（通用剥离，会把 {@code AUTO_INCREMENT} 关键字剥掉），
     * 再把结果交给 {@link #convert}。等本类看到语句时，自增关键字已经没了 ——
     * SQLite 的「下推成 INTEGER PRIMARY KEY AUTOINCREMENT」就无从下手，
     * 表现为「自增语义丢失」：列退化成普通列，插入时主键为 null，
     * 关联表的外键列随即报 NOT NULL 约束失败
     * （实测 edu_idiom.idiom_id → edu_idiom_grade.idiom_id）。</p>
     */
    @Override
    public List<String> convertAll(List<String> statements, String protocol) {
        if (statements == null || statements.isEmpty()) {
            return super.convertAll(statements, protocol);
        }
        List<String> prepared = new ArrayList<String>(statements.size());
        for (String statement : statements) {
            if (statement == null || statement.isBlank()) {
                prepared.add(statement);
                continue;
            }
            prepared.add(prepareAutoIncrement(statement));
        }
        return super.convertAll(prepared, protocol);
    }

    /**
     * 把建表语句里的自增列先改写成 SQLite 的写法（在父类剥离之前做）。
     *
     * @param statement 原语句
     * @return 改写后的语句；不是建表语句或没有自增列时原样返回
     */
    private String prepareAutoIncrement(String statement) {
        if (!statement.trim().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE")) {
            return statement;
        }
        String[] lines = statement.split("\n", -1);
        String autoIncrementColumn = null;
        int autoIncrementCount = 0;
        for (String line : lines) {
            Matcher matcher = AUTO_INCREMENT_COLUMN_LINE.matcher(line);
            if (matcher.matches()) {
                autoIncrementCount++;
                autoIncrementColumn = matcher.group(2);
            }
        }
        if (autoIncrementCount != 1 || autoIncrementColumn == null) {
            return statement;
        }
        if (!canPushDown(lines, autoIncrementColumn)) {
            return statement;
        }
        StringBuilder builder = new StringBuilder(statement.length());
        for (String line : lines) {
            if (TABLE_PRIMARY_KEY_LINE.matcher(line).matches()) {
                continue; // 主键下推到列上，表级声明要去掉（否则双主键）
            }
            if (isAutoIncrementLine(line, autoIncrementColumn)) {
                Matcher m = LEADING_COLUMN.matcher(line);
                if (m.find()) {
                    builder.append(m.group(1)).append(m.group(2)).append(AUTO_INCREMENT_COLUMN);
                    // 行尾逗号必须保留：丢了会让下一列与本列粘连成语法错
                    if (line.trim().endsWith(",")) {
                        builder.append(',');
                    }
                    builder.append('\n');
                    continue;
                }
            }
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    @Override
    public String convert(String statement, String protocol) {
        // SQLite 的自增列必须在本类里下推成「INTEGER PRIMARY KEY AUTOINCREMENT」，
        // 而父类的通用规则会先把 AUTO_INCREMENT 剥掉（其它方言没有 SQLite 的下推写法），
        // 所以要先拦住建表语句、在父类处理之前把自增信息取出来。
        boolean createTable = statement.trim().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE");
        String[] lines = createTable ? statement.split("\n", -1) : null;
        String autoIncrementColumn = null;
        int autoIncrementCount = 0;
        if (lines != null) {
            for (String line : lines) {
                Matcher matcher = AUTO_INCREMENT_COLUMN_LINE.matcher(line);
                if (matcher.matches()) {
                    autoIncrementCount++;
                    autoIncrementColumn = matcher.group(2);
                }
            }
        }
        String converted = super.convert(statement, protocol);
        if (converted == null || converted.isBlank()) {
            return converted;
        }
        // UUID 函数改写：SQLite 没有 UUID()
        converted = UUID_FUNCTION.matcher(converted).replaceAll(SQLITE_UUID);
        converted = BARE_UUID_FUNCTION.matcher(converted).replaceAll(SQLITE_UUID);
        String upper = converted.trim().toUpperCase();
        if (upper.startsWith("CREATE TABLE")) {
            converted = convertCreateTable(converted, autoIncrementCount, autoIncrementColumn);
        } else {
            converted = AUTO_INCREMENT_TOKEN.matcher(converted).replaceAll("");
        }
        converted = INSERT_IGNORE.matcher(converted).replaceAll("INSERT OR IGNORE");
        return convertUpsert(converted);
    }

    /**
     * CREATE TABLE 体内的自增列与表级主键改写。
     *
     * @param sql                已完成通用剥离的建表语句
     * @param autoIncrementCount 原语句里的自增列数量（父类已把 AUTO_INCREMENT 剥掉，这里用它的值）
     * @param autoIncrementColumn 原语句里的自增列名
     * @return SQLite 可执行的建表语句
     */
    private String convertCreateTable(String sql, int autoIncrementCount, String autoIncrementColumn) {
        String[] lines = sql.split("\n", -1);
        // 紧凑写法或多列自增无法安全下推，统一退化为剥离关键字
        boolean pushDown = autoIncrementCount == 1 && canPushDown(lines, autoIncrementColumn);
        if (autoIncrementCount == 0) {
            return AUTO_INCREMENT_TOKEN.matcher(sql).replaceAll("");
        }
        // 自增列在父类处理后已没有 AUTO_INCREMENT 关键字可比对，
        // 改用「列名相同」来定位那一行
        StringBuilder builder = new StringBuilder(sql.length());
        for (String line : lines) {
            if (pushDown && TABLE_PRIMARY_KEY_LINE.matcher(line).matches()) {
                continue; // 主键已下推到列上，保留表级声明会触发双主键错误
            }
            if (pushDown && isAutoIncrementLine(line, autoIncrementColumn)) {
                // 保留缩进与列名，把类型与约束整体换成 SQLite 的自增写法
                Matcher m = LEADING_COLUMN.matcher(line);
                if (m.find()) {
                    builder.append(m.group(1)).append(m.group(2))
                            .append(AUTO_INCREMENT_COLUMN);
                    // 行尾逗号必须保留：丢了会让下一列与本列粘连成语法错
                    if (line.trim().endsWith(",")) {
                        builder.append(',');
                    }
                    builder.append('\n');
                    continue;
                }
            }
            builder.append(AUTO_INCREMENT_TOKEN.matcher(line).replaceAll(""));
            builder.append('\n');
        }
        String result = builder.toString();
        // 表级主键行删除后可能遗留悬挂逗号
        return result.replaceAll(",\\s*\\)", ")");
    }

    /**
     * 该行是不是指定的自增列。
     *
     * @param line    建表语句的一行
     * @param column  自增列名
     * @return true 表示是
     */
    private boolean isAutoIncrementLine(String line, String column) {
        Matcher m = LEADING_COLUMN.matcher(line);
        return m.find() && sameIdentifier(m.group(2), column);
    }

    /**
     * 判断能否把主键下推到自增列上。
     *
     * <p>出现表级 {@code PRIMARY KEY} 声明时，只有它恰为与被转换列同名的单列主键才允许下推，
     * 否则（复合主键或异名列）下推会造成双主键；没有表级主键声明时允许下推。</p>
     *
     * @param lines  建表语句行
     * @param column 自增列名（可能带反引号）
     * @return true 表示可下推
     */
    private boolean canPushDown(String[] lines, String column) {
        for (String line : lines) {
            if (!TABLE_PRIMARY_KEY_ANY.matcher(line).find()) {
                continue;
            }
            Matcher matcher = TABLE_PRIMARY_KEY_LINE.matcher(line);
            return matcher.matches() && sameIdentifier(column, matcher.group(1));
        }
        return true;
    }

    /**
     * 标识符比较（忽略反引号与大小写）。
     *
     * @param left  标识符
     * @param right 标识符
     * @return 是否同名
     */
    private static boolean sameIdentifier(String left, String right) {
        return stripQuotes(left).equalsIgnoreCase(stripQuotes(right));
    }

    /**
     * 去掉反引号。
     *
     * @param identifier 标识符
     * @return 裸标识符
     */
    private static String stripQuotes(String identifier) {
        return identifier == null ? "" : identifier.replace("`", "").trim();
    }

    /**
     * {@code ON DUPLICATE KEY UPDATE} → SQLite upsert。
     *
     * @param sql 语句
     * @return 转换后的语句
     */
    private String convertUpsert(String sql) {
        Matcher matcher = ON_DUPLICATE_KEY_UPDATE.matcher(sql);
        if (!matcher.find()) {
            return sql;
        }
        String head = sql.substring(0, matcher.start());
        String tail = sql.substring(matcher.start());
        tail = ON_DUPLICATE_KEY_UPDATE.matcher(tail).replaceAll("ON CONFLICT DO UPDATE SET");
        return head + VALUES_FUNCTION.matcher(tail).replaceAll("excluded.$1");
    }
}
