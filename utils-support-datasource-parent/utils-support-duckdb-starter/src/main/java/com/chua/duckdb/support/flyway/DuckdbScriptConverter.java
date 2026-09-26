package com.chua.duckdb.support.flyway;

import com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter;
import com.chua.common.support.spi.annotations.Spi;

import java.util.regex.Pattern;

/**
 * DuckDB 迁移脚本方言转换器（{@code ScriptConverter} 的 duckdb 协议实现）。
 *
 * <p>本项目脚本统一以 MySQL 风格书写，而 DuckDB 是「PostgreSQL 语法 + SQLite 存储后端」的混合体：
 * 它接受 PG 系 DDL（{@code SERIAL}、{@code CREATE INDEX IF NOT EXISTS}、{@code ON CONFLICT}），
 * 但对 MySQL 的一批物理存储层语法完全没有对应物。与 SQLite 转换器相比，DuckDB 的差异面窄得多：
 * 它对列约束、类型别名、索引写法都比较宽容，真正会直接报语法错误的只有下面几类。</p>
 *
 * <h3>与 MySQL 的具体差异（逐条及依据）</h3>
 * <ul>
 *   <li><b>表尾物理属性</b>：{@code ENGINE=InnoDB}、{@code DEFAULT CHARSET=utf8mb4}、
 *       {@code COLLATE=utf8mb4_0900_ai_ci}、{@code ROW_FORMAT=DYNAMIC}、表级 {@code COMMENT='xxx'}、
 *       {@code AUTO_INCREMENT=100} 在 DuckDB 中均无对应物（DuckDB 无存储引擎插件、无字符集概念）。
 *       <b>依据</b>：这五类属性已由 {@link DefaultScriptConverter} 的
 *       {@code TAIL_ENGINE}/{@code TAIL_CHARACTER_SET}/{@code TAIL_COLLATE}/{@code TAIL_ROW_FORMAT}/
 *       {@code TAIL_COMMENT}/{@code TAIL_AUTO_INCREMENT} 在 {@code )} 之后的尾属性区统一剥离，
 *       本转换器不再重复实现，只在类级文档中确认该前提成立。</li>
 *   <li><b>行内索引声明与内联注释</b>：{@code KEY idx_x (col)}、{@code UNIQUE KEY uk_x (a,b)}、
 *       {@code INDEX idx_x (col)} 以及列级 {@code COMMENT 'xxx'} 同理由父类
 *       {@code INLINE_KEY_LINE}/{@code COLUMN_COMMENT} 逐行剥离（每列一行的对齐格式是前提）。
 *       独立的 {@code CREATE INDEX} 语句保留，DuckDB 原生支持。</li>
 *   <li><b>{@code USING BTREE} / {@code USING HASH}</b>：DuckDB 索引没有「索引组织方式」这一概念，
 *       也不接受 {@code USING HASH}（ART/索引类型由存储引擎内部决定，不可声明），出现即语法错误。
 *       <b>依据</b>：父类仅剥离「行内索引行」与「PRIMARY KEY 行尾」的 USING 子句，
 *       独立 {@code CREATE INDEX ... USING BTREE} 语句、以及被内联在同一行的 USING 均会漏网，
 *       故此处按关键字白名单 {@code BTREE|HASH} 兜底剥离。只匹配具名索引方式，
 *       不会误伤 JOIN 的 {@code ... USING (a, b)} 列表形式（后者带括号）。</li>
 *   <li><b>{@code ON UPDATE CURRENT_TIMESTAMP}</b>：MySQL 独有的列级自动更新时间戳，DuckDB 不支持
 *       （其等价能力需用 {@code DEFAULT current_timestamp} 配合触发器实现，属 DDL 之外的范畴）。
 *       <b>依据</b>：父类仅在 {@code CREATE TABLE} 分支剥离该子句，{@code ALTER TABLE ... MODIFY} 等
 *       其它语句中的同一子句不会被处理，故此处做全语句兜底剥离。</li>
 *   <li><b>列级 {@code AUTO_INCREMENT}</b>：DuckDB 无此关键字。保守做法是<b>只剥离关键字</b>，
 *       不做类型改写；DuckDB 的自增写法是 {@code col INTEGER PRIMARY KEY}（隐式依赖
 *       {@code rowid} 序列）或显式 {@code SERIAL} / {@code GENERATED ... AS IDENTITY}，
 *       而这些都要求改动列的类型或整条列定义，已超出「让脚本可执行」的最小改动范围。
 *       <b>依据与代价</b>：剥离后建表可执行，但该列不再自动编号；
 *       因此本转换器只保证建表可执行，DuckDB 侧的自增请在脚本中直接写 {@code SERIAL}。
 *       剥离规则同时兼容 MySQL 表尾的 {@code AUTO_INCREMENT=100} 形式（父类已在尾属性区处理，
 *       此处的可选 {@code = 数字} 分支仅为非 CREATE TABLE 场景兜底）。</li>
 *   <li><b>类型映射</b>：{@code MEDIUMTEXT/LONGTEXT/TINYTEXT/TEXT/JSON} → {@code TEXT}；
 *       {@code DATETIME} → {@code TIMESTAMP}；{@code DOUBLE} → {@code DOUBLE}（DuckDB 原生支持，
 *       仅显式列出以表明「已确认无需改写」）；{@code TINYINT} → {@code TINYINT}（DuckDB 原生别名，
 *       同样保留）。<b>依据</b>：DuckDB 无 MySQL 长度变体文本类型，统一收敛到 {@code TEXT}；
 *       {@code DATETIME} 在 DuckDB 中不是保留类型名（会被解析为
 *       {@code DATE '...'} 与 {@code TIME '...'} 的拼接语义），必须改写。</li>
 *   <li><b>函数映射</b>：{@code NOW()} → {@code CURRENT_TIMESTAMP}（父类 default 分支已做，此处保持一致）；
 *       {@code IFNULL(} → {@code COALESCE(}（DuckDB 无 IFNULL）；
 *       {@code RAND()} → {@code RANDOM()}（DuckDB 的随机函数名为 {@code random()}）。
 *       {@code CONCAT(a, b)} 保持不变：SQL 标准 {@code CONCAT} 语义，DuckDB 原生支持可变参。</li>
 * </ul>
 *
 * <p><b>不做整语句跳过</b>：与 Hive 不同，DuckDB 对 {@code INSERT IGNORE} /
 * {@code ON DUPLICATE KEY UPDATE} 之外的项目脚本常用写法均可执行；本转换器不返回 {@code null}，
 * 避免把本可执行的语句静默丢弃。</p>
 *
 * <h3>扩展键必须等于协议名</h3>
 * <p>注册方式为类上 {@code @Spi(value = "duckdb", order = 100)} + 本模块
 * {@code META-INF/extensions/com.chua.common.support.lang.datasource.flyway.ScriptConverter} 中的
 * {@code duckdb=} 行。原因见 {@code ScriptConverter} 的 SPI 文档：若改用
 * {@code ScriptConverter.SPI_NAME}（{@code script-converter}）作为扩展键，会与兜底默认实现
 * （{@code DefaultScriptConverter}，同为该键、{@code order=0}）同键竞争，
 * 同一键只保留 order 最高的一个，其余协议的转换会被整体遮蔽；按协议名区开后
 * {@code ScriptConverter.getExtension(protocol)} 才能稳定命中本实现。
 * {@link #supports(String)} 也因此只匹配 {@link #PROTOCOL}，避免被其它协议误命中。</p>
 *
 * <h3>前提</h3>
 * <p>{@code CREATE TABLE} 必须按每列一行的对齐格式书写（与项目 {@code db/init} 脚本及
 * {@code SHOW CREATE TABLE} 导出风格一致）：父类的行内索引剥离与列内联注释剥离都是<b>逐行正则</b>，
 * 单行紧凑写法（所有列挤在一行）不会被识别，建表将失败。本转换器自身不依赖行结构
 * （{@code AUTO_INCREMENT}/{@code USING}/{@code ON UPDATE} 均为全语句正则），但仍以该格式为前提。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = DuckdbScriptConverter.PROTOCOL, order = 100)
public class DuckdbScriptConverter extends DefaultScriptConverter {

    /**
     * 本协议名（同时作为 SPI 扩展键）
     */
    public static final String PROTOCOL = "duckdb";

    /**
     * 索引组织方式子句：{@code USING BTREE} / {@code USING HASH}（大小写不敏感，可重复出现）。
     * DuckDB 无索引组织方式概念，出现即语法错误；只匹配具名方式，不会误伤
     * JOIN 的 {@code ... USING (a, b)} 列表形式
     */
    private static final Pattern USING_INDEX_TYPE = Pattern.compile(
            "\\s+USING\\s+(?:BTREE|HASH)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 列级自动更新时间戳：{@code ON UPDATE CURRENT_TIMESTAMP}（MySQL 专属，DuckDB 不支持）
     */
    private static final Pattern ON_UPDATE_CURRENT_TIMESTAMP = Pattern.compile(
            "\\s+ON\\s+UPDATE\\s+CURRENT_TIMESTAMP\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 任意位置的 {@code AUTO_INCREMENT} 关键字（DuckDB 无此语法，只剥离不改写列类型），
     * 可选分支 {@code = 数字} 用于兼容 MySQL 表尾自增起始值形式
     */
    private static final Pattern AUTO_INCREMENT_TOKEN = Pattern.compile(
            "\\s*\\bAUTO_INCREMENT\\b(?:\\s*=\\s*\\d+)?", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(String protocol) {
        return PROTOCOL.equalsIgnoreCase(protocol);
    }

    @Override
    public String convert(String statement, String protocol) {
        String converted = super.convert(statement, protocol);
        if (converted == null || converted.isBlank()) {
            return converted;
        }
        // 1. 索引组织方式：DuckDB 无 USING BTREE / USING HASH
        converted = USING_INDEX_TYPE.matcher(converted).replaceAll("");
        // 2. 列级自动更新时间戳：全语句兜底（父类仅在 CREATE TABLE 分支处理）
        converted = ON_UPDATE_CURRENT_TIMESTAMP.matcher(converted).replaceAll("");
        // 3. 自增关键字：只剥离，DuckDB 自增请用 SERIAL / IDENTITY
        converted = AUTO_INCREMENT_TOKEN.matcher(converted).replaceAll("");
        // 4. 类型映射
        converted = convertTypes(converted);
        // 5. 函数映射
        return convertFunctions(converted);
    }

    /**
     * DuckDB 类型映射。
     *
     * <p>{@code MEDIUMTEXT/LONGTEXT/TINYTEXT/TEXT/JSON} → {@code TEXT}；{@code DATETIME} →
     * {@code TIMESTAMP}。{@code \bTEXT\b} 在 {@code MEDIUMTEXT} 内部不构成词边界，
     * 但仍先替换长度变体再替换 {@code TEXT}，以规避正则语义上的顺序耦合。
     * {@code DOUBLE} 与 {@code TINYINT} DuckDB 原生支持，显式替换为同名字面量以表明已确认。</p>
     *
     * @param sql 语句
     * @return 类型映射后的语句
     */
    private String convertTypes(String sql) {
        String result = sql;
        result = result.replaceAll("(?i)\\bMEDIUMTEXT\\b", "TEXT");
        result = result.replaceAll("(?i)\\bLONGTEXT\\b", "TEXT");
        result = result.replaceAll("(?i)\\bTINYTEXT\\b", "TEXT");
        result = result.replaceAll("(?i)\\bTEXT\\b", "TEXT");
        result = result.replaceAll("(?i)\\bJSON\\b", "TEXT");
        result = result.replaceAll("(?i)\\bDATETIME\\b", "TIMESTAMP");
        result = result.replaceAll("(?i)\\bDOUBLE\\b", "DOUBLE");
        result = result.replaceAll("(?i)\\bTINYINT\\b", "TINYINT");
        return result;
    }

    /**
     * DuckDB 函数映射。
     *
     * <p>{@code NOW()} → {@code CURRENT_TIMESTAMP}；{@code IFNULL(} → {@code COALESCE(}；
     * {@code RAND()} → {@code RANDOM()}。{@code CONCAT(a, b)} 不做改写：
     * SQL 标准可变参 {@code CONCAT} 在 DuckDB 原生可用。</p>
     *
     * @param sql 语句
     * @return 函数映射后的语句
     */
    private String convertFunctions(String sql) {
        String result = sql;
        result = result.replaceAll("(?i)\\bNOW\\s*\\(\\s*\\)", "CURRENT_TIMESTAMP");
        result = result.replaceAll("(?i)\\bIFNULL\\s*\\(", "COALESCE(");
        result = result.replaceAll("(?i)\\bRAND\\s*\\(\\s*\\)", "RANDOM()");
        return result;
    }
}
