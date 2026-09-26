package com.chua.datasource.support.flyway;

import com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter;
import com.chua.common.support.spi.annotations.Spi;

import java.util.regex.Pattern;

/**
 * Apache Hive 迁移脚本方言转换器（{@code ScriptConverter} 的 hive 协议实现）。
 *
 * <p>本项目脚本统一以 MySQL 风格书写，而 Hive 是「类 MySQL 接口 + 类 PostgreSQL 实现」的窄方言：
 * 它只认 {@code CREATE TABLE}、{@code INSERT [INTO|OVERWRITE]}、{@code DROP}、
 * {@code ALTER TABLE ... ADD COLUMNS} 等极少数语句形态，MySQL 的行内索引、内联注释、
 * 自增、存储引擎、字符集以及行级 upsert 全部没有等价物。因此本转换器的策略是
 * <b>「能安全改写的改写，改写不了的整条跳过」</b>，宁可少执行一条语句，也不让迁移在半路报错中断。</p>
 *
 * <h3>与 MySQL 的具体差异（逐条及依据）</h3>
 * <ul>
 *   <li><b>表尾物理属性</b>：{@code ENGINE=InnoDB}、{@code AUTO_INCREMENT=100}、
 *       {@code CHARSET=utf8mb4}/{@code CHARACTER SET=}、{@code COLLATE=}、{@code ROW_FORMAT=DYNAMIC}、
 *       表级 {@code COMMENT='xxx'} 在 Hive 中均无对应物（Hive 无存储引擎插件、无字符集、
 *       无自增起始值、无表注释语法）。<b>依据</b>：前六者已由 {@link DefaultScriptConverter} 的
 *       {@code TAIL_ENGINE}/{@code TAIL_AUTO_INCREMENT}/{@code TAIL_CHARACTER_SET}/{@code TAIL_COLLATE}/
 *       {@code TAIL_ROW_FORMAT}/{@code TAIL_COMMENT} 在 {@code )} 之后的尾属性区统一剥离，
 *       本转换器不再重复实现，仅确认该前提成立。</li>
 *   <li><b>行内索引声明与内联注释</b>：{@code KEY idx_x (col)}、{@code UNIQUE KEY uk_x (a,b)}、
 *       {@code INDEX idx_x (col)}、{@code PRIMARY KEY (`id`) USING BTREE} 的 USING 尾子句、
 *       以及列级 {@code COMMENT 'xxx'} 由父类逐行剥离（{@code INLINE_KEY_LINE}/{@code PK_USING}/
 *       {@code COLUMN_COMMENT}）。Hive 侧等价物是 {@code ALTER TABLE ... ADD COLUMNS (...)} 与
 *       「列名 + {@code COMMENT 'xxx'}」的混合写法，不在转换范围内（保持最小改动）。</li>
 *   <li><b>{@code ON DUPLICATE KEY UPDATE} → 整条跳过（返回 {@code null}）</b>。
 *       <b>依据</b>：Hive 无 {@code INSERT ... ON DUPLICATE KEY UPDATE}；
 *       ACID 表可用 {@code MERGE INTO ... USING ... ON ... WHEN MATCHED THEN UPDATE} 替代，
 *       但改写需要预知目标表主键、源数据子句与逐列 SET 列表，机械改写会产出语义不等价甚至
 *       列数不匹配的语句，风险远大于收益；Hive 也无 {@code INSERT OVERWRITE ... SELECT ... WHERE 1=0}
 *       这类「等价空写」惯用法（该形式会把 INSERT 语义整个换掉）。
 *       因此选择跳过：项目脚本中的 upsert 语句用于初始化幂等补数，跳过后由紧随其后的
 *       全量 {@code INSERT OVERWRITE} 承担数据装载，不会造成结构缺列。</li>
 *   <li><b>{@code INSERT IGNORE} → 整条跳过（返回 {@code null}）</b>。
 *       <b>依据</b>：Hive 支持的插入形态是 {@code INSERT [INTO] table ...} / {@code INSERT OVERWRITE}，
 *       既没有 {@code IGNORE} 修饰，也没有 SQLite 的 {@code INSERT OR IGNORE} 变体。
 *       同样不做「{@code INSERT OVERWRITE TABLE ... SELECT ... WHERE 1=0}」改写：
 *       该改写会引入脚本中不存在的 {@code SELECT} 子句，且要求列清单完整，
 *       一旦列数变化就会静默写错位数据，属于高风险改写。跳过是可预期的失败面。</li>
 *   <li><b>{@code ALTER TABLE ... ADD COLUMN [IF NOT EXISTS]} → 整条跳过（返回 {@code null}）</b>。
 *       <b>依据</b>：Hive 支持 {@code ALTER TABLE t ADD COLUMNS (c1 type, c2 type)}，
 *       但（a）关键字是复数 {@code COLUMNS}，单数 {@code ADD COLUMN} 语法错误；
 *       （b）Hive 的 {@code ADD COLUMNS} <b>没有</b> {@code IF NOT EXISTS} 幂等修饰，
 *       列已存在时直接报「列已存在」；而（c）单列改写还需要补出括号包裹的
 *       {@code ADD COLUMNS (c type)} 形式并处理 {@code AFTER col} 定位（Hive 的
 *       {@code ADD COLUMNS (c type, AFTER pre)}），三者叠加使得自动改写的正确性无法保证。
 *       跳过理由与 MySQL 转换器互补：MySQL 侧把该语句展开为
 *       {@code information_schema} 守卫的动态 SQL（列已存在时自动空操作），
 *       而 Hive 连「查询系统表判断列是否存在」的通用手段都没有（Hive 的
 *       {@code DESCRIBE} 无法在纯 DDL 位置做守卫），因此只能跳过。
 *       依赖该语句补列的存量 Hive 库需人工执行等价 {@code ADD COLUMNS}。</li>
 *   <li><b>类型映射</b>：{@code MEDIUMTEXT/LONGTEXT/TINYTEXT/TEXT/JSON} → {@code STRING}；
 *       {@code DATETIME}/{@code TIMESTAMP} → {@code TIMESTAMP}；{@code DOUBLE} → {@code DOUBLE}；
 *       {@code BLOB/BINARY/VARBINARY} → {@code BINARY}；{@code TINYINT} → {@code TINYINT}（原生支持）。
 *       <b>依据</b>：Hive 只有 {@code STRING} 一种字符串类型（无长度变体），
 *       无 {@code TEXT} 系列与 {@code JSON}（复杂类型需 {@code STRUCT}/{@code MAP}/{@code ARRAY}，
 *       不能由 JSON 文本自动推导，故收敛为 {@code STRING}）；
 *       {@code DATETIME} 不是 Hive 保留类型名，必须改写；
 *       Hive 无 {@code BLOB}/{@code VARBINARY}，二进制统一为 {@code BINARY}。</li>
 *   <li><b>函数映射</b>：{@code NOW()} → {@code CURRENT_TIMESTAMP}（Hive 有 {@code current_timestamp}）；
 *       {@code IFNULL(} → {@code COALESCE(}（Hive 无 IFNULL）；
 *       {@code CONCAT(a, b)} <b>保持不变</b>——Hive 的 {@code concat} 与 SQL 标准一致（可变参），
 *       只有 {@code concat_ws}（带分隔符）在两侧有差异，因此不做任何改写。</li>
 * </ul>
 *
 * <p><b>已知未覆盖项（如实声明，不做静默处理）</b>：{@code CHAR}/{@code VARCHAR} 未映射为
 * {@code STRING}。Hive 无这两种类型，按同样逻辑应映射，但本转换器遵循「规则集显式枚举、
 * 不做隐式外推」的原则，故保持原样；若目标 Hive 库出现该类型，需在脚本中直接写
 * {@code STRING} 或显式扩展本类。</p>
 *
 * <h3>扩展键必须等于协议名</h3>
 * <p>注册方式为类上 {@code @Spi(value = "hive", order = 100)} + 本模块
 * {@code META-INF/extensions/com.chua.common.support.lang.datasource.flyway.ScriptConverter} 中的
 * {@code hive=} 行。原因见 {@code ScriptConverter} 的 SPI 文档：若改用
 * {@code ScriptConverter.SPI_NAME}（{@code script-converter}）作为扩展键，会与兜底默认实现
 * （{@code DefaultScriptConverter}，同为该键、{@code order=0}）同键竞争，
 * 同一键只保留 order 最高的一个，其余协议的转换会被整体遮蔽；按协议名区开后
 * {@code ScriptConverter.getExtension(protocol)} 才能稳定命中本实现。
 * {@link #supports(String)} 也因此只匹配 {@link #PROTOCOL}，避免被其它协议误命中。</p>
 *
 * <h3>前提</h3>
 * <p>{@code CREATE TABLE} 必须按每列一行的对齐格式书写（与项目 {@code db/init} 脚本及
 * {@code SHOW CREATE TABLE} 导出风格一致）：父类的行内索引剥离与列内联注释剥离都是<b>逐行正则</b>，
 * 单行紧凑写法（所有列挤在一行）不会被识别，建表将失败。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = HiveScriptConverter.PROTOCOL, order = 100)
public class HiveScriptConverter extends DefaultScriptConverter {

    /**
     * 本协议名（同时作为 SPI 扩展键）
     */
    public static final String PROTOCOL = "hive";

    /**
     * MySQL 行级 upsert：{@code ON DUPLICATE KEY UPDATE}。Hive 无等价语法，整条语句跳过
     */
    private static final Pattern ON_DUPLICATE_KEY_UPDATE = Pattern.compile(
            "\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code INSERT IGNORE}。Hive 的插入形态只有 {@code INSERT}/{@code INSERT OVERWRITE}，
     * 无 {@code IGNORE} 与 {@code OR IGNORE} 变体，整条语句跳过
     */
    private static final Pattern INSERT_IGNORE = Pattern.compile(
            "\\bINSERT\\s+(?:OR\\s+)?IGNORE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Hive 不支持的幂等补列语句：{@code ALTER TABLE t ADD COLUMN [IF NOT EXISTS] c 定义}。
     * Hive 支持的补列语法是 {@code ADD COLUMNS (...)}（复数、必须括号包裹、且无 {@code IF NOT EXISTS}），
     * 自动改写需同时处理这三处差异，正确性无法保证，故整条跳过。
     * 正则带 {@code DOTALL}，兼容多列 {@code ADD COLUMN a ..., ADD COLUMN b ...} 写在同一语句的情况
     */
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+.*\\bADD\\s+COLUMNS?\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
        // 1. MySQL 行级 upsert：Hive 无等价语法，跳过整条
        if (ON_DUPLICATE_KEY_UPDATE.matcher(converted).find()) {
            return null;
        }
        // 2. INSERT IGNORE：Hive 无 IGNORE 变体，且不做高风险的等价空写改写，跳过整条
        if (INSERT_IGNORE.matcher(converted).find()) {
            return null;
        }
        // 3. ALTER TABLE ... ADD COLUMN(S)：Hive 无 IF NOT EXISTS 幂等修饰，跳过整条
        if (ADD_COLUMN.matcher(converted).find()) {
            return null;
        }
        return convertFunctions(convertTypes(converted));
    }

    /**
     * Hive 类型映射。
     *
     * <p>{@code MEDIUMTEXT/LONGTEXT/TINYTEXT/TEXT/JSON} → {@code STRING}；
     * {@code DATETIME}/{@code TIMESTAMP} → {@code TIMESTAMP}；
     * {@code BLOB/BINARY/VARBINARY} → {@code BINARY}；{@code DOUBLE}/{@code TINYINT} Hive 原生支持，
     * 显式替换为同名字面量以表明「已确认无需改写」。
     * 先替换长度变体再替换 {@code TEXT}，规避正则语义上的顺序耦合。</p>
     *
     * @param sql 语句
     * @return 类型映射后的语句
     */
    private String convertTypes(String sql) {
        String result = sql;
        result = result.replaceAll("(?i)\\bMEDIUMTEXT\\b", "STRING");
        result = result.replaceAll("(?i)\\bLONGTEXT\\b", "STRING");
        result = result.replaceAll("(?i)\\bTINYTEXT\\b", "STRING");
        result = result.replaceAll("(?i)\\bTEXT\\b", "STRING");
        result = result.replaceAll("(?i)\\bJSON\\b", "STRING");
        result = result.replaceAll("(?i)\\bDATETIME\\b", "TIMESTAMP");
        result = result.replaceAll("(?i)\\bTIMESTAMP\\b", "TIMESTAMP");
        result = result.replaceAll("(?i)\\bBLOB\\b", "BINARY");
        result = result.replaceAll("(?i)\\bVARBINARY\\b", "BINARY");
        result = result.replaceAll("(?i)\\bBINARY\\b", "BINARY");
        result = result.replaceAll("(?i)\\bDOUBLE\\b", "DOUBLE");
        result = result.replaceAll("(?i)\\bTINYINT\\b", "TINYINT");
        return result;
    }

    /**
     * Hive 函数映射。
     *
     * <p>{@code NOW()} → {@code CURRENT_TIMESTAMP}；{@code IFNULL(} → {@code COALESCE(}；
     * {@code CONCAT(a, b)} 不做改写（SQL 标准可变参语义，Hive 原生支持）。</p>
     *
     * @param sql 语句
     * @return 函数映射后的语句
     */
    private String convertFunctions(String sql) {
        String result = sql;
        result = result.replaceAll("(?i)\\bNOW\\s*\\(\\s*\\)", "CURRENT_TIMESTAMP");
        result = result.replaceAll("(?i)\\bIFNULL\\s*\\(", "COALESCE(");
        return result;
    }
}
