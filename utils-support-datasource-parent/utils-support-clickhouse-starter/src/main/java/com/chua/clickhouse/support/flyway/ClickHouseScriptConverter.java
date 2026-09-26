package com.chua.clickhouse.support.flyway;

import com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter;
import com.chua.common.support.spi.annotations.Spi;

import java.util.regex.Pattern;

/**
 * ClickHouse 迁移脚本方言转换器（{@code ScriptConverter} 的 clickhouse 协议实现）。
 *
 * <p>项目脚本统一以 MySQL 风格书写（{@code ENGINE=}、{@code AUTO_INCREMENT}、行内
 * {@code KEY/UNIQUE KEY}、列内联 {@code COMMENT}、MySQL 整型宽度、MIME 文本类型等），
 * 而 ClickHouse 是列式分析库，其建表语法与 MySQL 存在系统性差异，直接执行必然报语法错误。
 * 本类在 {@link DefaultScriptConverter} 的通用剥离规则之上，补齐 ClickHouse 特有的
 * 类型体系与语法差异，使同一套 {@code db/init} 脚本可在 ClickHouse 上运行。</p>
 *
 * <h3>ClickHouse 与 MySQL 的具体差异</h3>
 * <ul>
 *   <li><b>表尾属性</b>：{@code ENGINE=} / {@code AUTO_INCREMENT=} / {@code CHARACTER SET=} /
 *       {@code CHARSET=} / {@code COLLATE=} / {@code ROW_FORMAT=} / 表级 {@code COMMENT='...'}
 *       全部是 MySQL 专属，ClickHouse 的 {@code CREATE TABLE} 尾部只接受
 *       {@code ENGINE = MergeTree}（或 {@code Replicated*} / {@code Null} / {@code Memory} 等）
 *       及其 {@code ORDER BY} / {@code PRIMARY KEY} / {@code PARTITION BY} 子句，不存在上述任何属性。
 *       <b>依据</b>：ClickHouse 官方 {@code CREATE TABLE} 语法中，{@code ) ENGINE = ...} 之后
 *       只允许引擎参数列表，{@code AUTO_INCREMENT}/{@code ROW_FORMAT}/{@code CHARACTER SET}
 *       均为 InnoDB/MyISAM 的存储引擎私有语法。
 *       <b>处理</b>：已由 {@link DefaultScriptConverter} 的 {@code TAIL_ENGINE} /
 *       {@code TAIL_AUTO_INCREMENT} / {@code TAIL_CHARACTER_SET} / {@code TAIL_COLLATE} /
 *       {@code TAIL_ROW_FORMAT} / {@code TAIL_COMMENT} 剥离，本类不重复实现，只做确认。</li>
 *
 *   <li><b>行内索引声明</b>：{@code UNIQUE KEY uk_x (a,b)}、{@code KEY idx_x (c)} 是
 *       {@code SHOW CREATE TABLE} 的 MySQL 导出风格（索引定义内联在建表体里）。
 *       ClickHouse 没有「引擎无关的二级索引内联声明」这一形态，索引一律是
 *       {@code CREATE INDEX ... ON tbl (col) TYPE ...} 独立语句，或 {@code ALTER TABLE ... ADD INDEX}。
 *       <b>依据</b>：ClickHouse {@code CREATE TABLE} 表体只接受列定义、{@code INDEX}（需带
 *       {@code TYPE} 表达式）与约束，内联 {@code KEY name (cols)} 会被解析为未知元素。
 *       <b>处理</b>：已由 {@link DefaultScriptConverter} 的 {@code INLINE_KEY_LINE} 覆盖
 *       （其可选前缀含 {@code UNIQUE\s+}，即 {@code UNIQUE KEY uk (...)} 同样命中），本类只做确认。
 *       独立的 {@code CREATE INDEX} 语句在 ClickHouse 上若带 {@code IF NOT EXISTS} 同样不合法，
 *       属本转换范围之外（见下方「未覆盖范围」）。</li>
 *
 *   <li><b>{@code AUTO_INCREMENT} 关键字</b>：ClickHouse 没有自增列概念，也不存在自增序列
 *       （ClickHouse 的自增需用 {@code Sequence} 引擎或 {@code GenerateUniqueID()} 物化视图实现，
 *       且都不是列定义内的关键字）。列上写 {@code AUTO_INCREMENT} 直接语法错误。
 *       <b>依据</b>：{@code AUTO_INCREMENT} 是 MySQL 存储引擎属性，ClickHouse 列语法中无该词。
 *       <b>处理</b>：本类用 {@link #AUTO_INCREMENT_TOKEN} 剥离列级 {@code AUTO_INCREMENT}
 *       与表尾 {@code AUTO_INCREMENT=100}（含 {@code =数字} 可选部分，参考
 *       {@code SqliteScriptConverter} 的同名常量写法）。与 SQLite 不同，ClickHouse 没有
 *       「自增必须写成 {@code INTEGER PRIMARY KEY AUTOINCREMENT}」的硬约束，因此无需下推主键、
 *       无需删除表级 {@code PRIMARY KEY (...)} 行，单纯剥离即可。</li>
 *
 *   <li><b>{@code ON UPDATE CURRENT_TIMESTAMP}</b>：这是 MySQL/InnoDB 的「列值更新时自动刷新」
 *       扩展，ClickHouse 作为不可变行的列式存储没有此语义，解析器也不识别该子句。
 *       <b>依据</b>：{@code ON UPDATE CURRENT_TIMESTAMP} 属 MySQL 列级选项，SQL 标准与
 *       ClickHouse 均无对应语法。
 *       <b>处理</b>：本类用 {@link #ON_UPDATE_CURRENT_TIMESTAMP} 剥离。
 *       注意 {@code DefaultScriptConverter} 仅在 {@code CREATE TABLE} 分支内剥离该子句，
 *       {@code ALTER TABLE} / {@code INSERT} / {@code UPDATE} 语句不会经过该分支，
 *       故本类的剥离是全局生效的补齐。</li>
 *
 *   <li><b>整型类型</b>：MySQL 的 {@code TINYINT/SMALLINT/MEDIUMINT/INT/BIGINT}
 *       带「显示宽度」（{@code INT(11)}、{@code BIGINT(20)}、{@code TINYINT(1)}），
 *       宽度只是显示提示、不影响取值范围；ClickHouse 的 {@code Int8/Int16/Int32/Int64}
 *       是定长类型，<b>不接受任何括号参数</b>（{@code Int32(11)} 是语法错误）。
 *       <b>依据</b>：ClickHouse 类型名后不带长度/精度修饰（唯一例外是 {@code Decimal(p,s)}、
 *       {@code FixedString(N)}、{@code DateTime64(p)}）。
 *       <b>处理</b>：{@link #INTEGER_DISPLAY_WIDTH} 先剥离整型显示宽度，再按
 *       {@link #TYPE_TINYINT} 等常量映射为 {@code Int8/Int16/Int32/Int64}。
 *       逐值范围保持一致：TINYINT(有符号 −128~127)→{@code Int8}、
 *       SMALLINT(−32768~32767)→{@code Int16}、MEDIUMINT 与 INT(−2147483648~2147483647)→{@code Int32}、
 *       BIGINT→{@code Int64}。
 *       <b>语义取舍</b>：{@code INT(11)} 之类宽度被丢弃是安全的，因为它在 MySQL 中不影响存储与比较；
 *       但 {@code INT UNSIGNED}（无符号）超出 {@code Int32} 上界，本类不做放大到 {@code UInt32} 的处理，
 *       理由是无符号语义无法仅凭类型名判断（脚本里可能出现 {@code BIGINT UNSIGNED} 作为
 *       {@code Int64} 自增主键，超界风险低），如需精确控制应在脚本中直接写 ClickHouse 类型。</li>
 *
 *   <li><b>时间类型</b>：{@code DATETIME} / {@code TIMESTAMP}（含 {@code DATETIME(3)} 精度形式）
 *       → {@link #TYPE_DATETIME_TIME} 映射为 {@code DateTime}。{@link #TIME_PRECISION}
 *       负责剥离精度括号。ClickHouse 侧对应的高精度类型是 {@code DateTime64(3)}，
 *       本类按「保守可执行」原则统一落到秒精度的 {@code DateTime}：{@code DateTime} 在
 *       0~2106 区间与 MySQL {@code DATETIME} 范围一致，且不依赖服务器的
 *       {@code DateTime64} 时区扩展；代价是毫秒精度被截断。
 *       补齐精度需求时，把脚本中的列直接写成 {@code DateTime64(3)} 即可（映射只匹配 MySQL 类型名，
 *       不会二次改写已合规的 ClickHouse 类型）。</li>
 *
 *   <li><b>文本与 JSON 类型</b>：{@code TINYTEXT/TEXT/MEDIUMTEXT/LONGTEXT}、
 *       {@code TINYBLOB/BLOB/MEDIUMBLOB/LONGBLOB}、{@code BINARY}、{@code VARBINARY}、
 *       {@code JSON} 全部映射为 {@link #TYPE_STRING}（{@code String}）。
 *       <b>依据</b>：ClickHouse 没有按长度分级（{@code TINYTEXT}/{@code LONGTEXT}）的字符串类型，
 *       字符串长度不受声明限制，{@code String} 是等价的无界字节串；
 *       二进制列在 ClickHouse 同样用 {@code String} 承载（写入需保证字节序列可解析为合法 UTF-8，
 *       任意二进制内容应改用 {@code FixedString(N)} 或把列声明为 {@code String} + 自行约定编码）；
 *       {@code JSON} 虽在新版 ClickHouse 中作为实验特性存在，但要求开启
 *       {@code allow_experimental_json_type=1}，为避免依赖实验开关，统一落到 {@code String}
 *       （与 {@code DefaultScriptConverter} 对 SQLite/H2 的 {@code JSON→TEXT} 保守策略一致）。
 *       映射按「长前缀优先」顺序执行（{@code LONGTEXT} 先于 {@code TEXT}），
 *       虽有 {@code \b} 词边界已能避免 {@code TEXT} 误命中 {@code LONGTEXT} 内子串，
 *       但保持顺序可读且不影响结果。</li>
 *
 *   <li><b>浮点类型</b>：{@code DOUBLE} / {@code FLOAT} / {@code REAL} → {@link #TYPE_FLOAT}
 *       （{@code Float64}）。{@link #FLOAT_PRECISION} 剥离 {@code FLOAT(10,2)} 的精度括号
 *       （ClickHouse 浮点类型不接收参数）。语义取舍：{@code FLOAT}/{@code REAL} 在 MySQL 中是单精度，
 *       映射为 {@code Float64} 会放宽到双精度，数值本身仍相等，仅存储占用变大；
 *       反向（{@code DOUBLE}→单精度）会造成精度丢失，故一律放宽。</li>
 *
 *   <li><b>函数</b>：{@code NOW()} → {@link #FUNCTION_NOW}（{@code now()}）、
 *       {@code IFNULL(a,b)} → {@link #FUNCTION_IFNULL}（{@link #TYPE_STRING} 不相关，
 *       实际写入 {@code ifNull(a,b)}）。二者都是 ClickHouse 的<b>大小写敏感</b>驼峰函数，
 *       写成大写 {@code IFNULL}/{@code NOW} 在 ClickHouse 中<b>不是</b>内置函数
 *       （ClickHouse 内置函数名区分大小写，未匹配到同名 UDF 时报
 *       {@code Function not found}），因此必须改写。
 *       <b>前置事实</b>：{@link DefaultScriptConverter} 的 {@code default} 分支已会把
 *       {@code NOW()} 映射为 {@code CURRENT_TIMESTAMP}，而 {@code CURRENT_TIMESTAMP}
 *       是 ClickHouse 合法写法（等价于 {@code now()}），因此本类的 {@code NOW} 规则
 *       在当前调用链（先 {@code super.convert}）下通常已是幂等兜底、不再命中；
 *       保留它是为了在父类映射策略调整后仍能兜住。{@code IFNULL} 则确实需要本类改写，
 *       因为父类 {@code default} 分支不处理它。{@code CONCAT} 与 {@code CURRENT_TIMESTAMP}
 *       在 ClickHouse 中名称与语义均一致，<b>原样保留</b>。</li>
 *
 *   <li><b>反引号标识符</b>：ClickHouse 解析器默认接受反引号包裹的标识符
 *       （与 MySQL 同），无需改为双引号，<b>原样保留</b>。</li>
 *
 *   <li><b>{@code INSERT IGNORE}</b>：ClickHouse 的 {@code INSERT} 支持
 *       {@code IGNORE} 修饰（与 {@code VALUES} 一起构成 {@code INSERT IGNORE INTO ...}），
 *       语义为「忽略不可解析的行而非整体失败」，与写入幂等诉求一致，<b>原样保留</b>。</li>
 * </ul>
 *
 * <h3>{@code ADD COLUMN IF NOT EXISTS}：返回 {@code null} 跳过</h3>
 * <p>ClickHouse 的 {@code ALTER TABLE ... ADD COLUMN} 不接受 {@code IF NOT EXISTS}，
 * 带该子句必然语法错误。可选方案有两条：</p>
 * <ol>
 *   <li>改写为「元数据守卫」的动态 SQL（{@code MysqlScriptConverter} 采用的方案）——
 *       <b>不适用</b>：该方案依赖 {@code information_schema.COLUMNS} 与
 *       {@code PREPARE}/{@code EXECUTE} 动态 SQL 能力，而 ClickHouse
 *       <b>没有 {@code information_schema}（也没有 {@code SHOW COLUMNS} 的等价可编程查询）</b>，
 *       也没有会话级动态 SQL 机制，元数据只能通过 {@code system.columns}
 *       （列名为 snake_case 风格：{@code database}/{@code table}/{@code column}）读取，
 *       拼一条守卫 SQL 需要手写多段子查询，与 Flyway 的逐条语句执行模型不兼容，
 *       也会让迁移脚本在 ClickHouse 上强依赖 {@code system} 表权限。</li>
 *   <li>整条跳过（返回 {@code null}）——本类采用。理由：本项目脚本的列定义已由
 *       {@code CREATE TABLE} 全量覆盖，这些 {@code ADD COLUMN IF NOT EXISTS}
 *       属于「为其它方言准备的幂等冗余」，跳过不会造成结构缺失；
 *       若强行执行，去掉 {@code IF NOT EXISTS} 后在重复迁移时会因列已存在而报错，
 *       反而破坏幂等。</li>
 * </ol>
 *
 * <p><b>未覆盖范围</b>：{@code CREATE INDEX IF NOT EXISTS} / {@code DROP INDEX IF EXISTS}
 * 与 {@code CREATE TABLE IF NOT EXISTS} 同样不被 ClickHouse 接受，但本类不做处理，
 * 项目脚本中不存在这些写法（索引一律用不带守卫子句的独立 {@code CREATE INDEX}），
 * 如后续脚本引入，需按同一「ClickHouse 无 {@code information_schema}」的理由决定跳过或改写。</p>
 *
 * <h3>扩展键必须等于协议名</h3>
 * <p>{@code ScriptConverter} 的 SPI 选择顺序是：先以<b>协议名</b>为键查精确扩展，
 * 查不到再遍历其余扩展键取首个 {@code supports} 命中者，最后回落到 {@code @SpiDefault} 兜底。
 * 因此本类的扩展键必须是 {@code clickhouse}（即 {@code @Spi("clickhouse")} 与本模块
 * {@code META-INF/extensions/com.chua.common.support.lang.datasource.flyway.ScriptConverter}
 * 中的 {@code clickhouse=} 行）。若误用 {@code ScriptConverter.SPI_NAME}
 * （{@code script-converter}）作为扩展键，会与兜底的
 * {@code DefaultScriptConverter} 撞成同键，优先级高的那一个胜出，
 * 其它协议的转换会被整体遮蔽（表现为 sqlite/h2 等方言转换全部失效）。</p>
 *
 * <p><b>前提</b>：{@code CREATE TABLE} 按<b>每列一行</b>的对齐格式书写（列定义各占一行，
 * 索引/约束各占一行），与本项目 {@code db/init} 脚本及 {@code SHOW CREATE TABLE} 导出风格一致。
 * 这一点对本类是必需前提而非可选约定：行内 {@code KEY}/{@code UNIQUE KEY} 的整行剥离
 * （{@code DefaultScriptConverter} 的 {@code INLINE_KEY_LINE} 用 {@code ^...$} 整行锚定）、
 * 列内联 {@code COMMENT} 的行尾剥离都依赖「一条语句一行」；单行紧凑写法下
 * 索引声明不会被剥离，建表在 ClickHouse 上必然失败。
 * 另需注意：ClickHouse 的 {@code ENGINE} 子句是<b>必须</b>的（无默认引擎），
 * 本类只剥离 MySQL 尾属性、不负责合成 {@code ENGINE = MergeTree}；
 * 脚本若没有引擎子句，需由 ClickHouse 侧配置或脚本本身补齐。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = ClickHouseScriptConverter.PROTOCOL, order = 100)
public class ClickHouseScriptConverter extends DefaultScriptConverter {

    /**
     * 本协议名（同时作为 SPI 扩展键，必须与 {@code META-INF/extensions} 中的 {@code clickhouse=} 一致）
     */
    public static final String PROTOCOL = "clickhouse";

    /**
     * ClickHouse 字符串类型
     */
    private static final String TYPE_STRING = "String";

    /**
     * ClickHouse 64 位浮点类型
     */
    private static final String TYPE_FLOAT = "Float64";

    /**
     * ClickHouse 秒精度日期时间类型
     */
    private static final String TYPE_DATETIME_TIME = "DateTime";

    /**
     * ClickHouse 当前时间函数
     */
    private static final String FUNCTION_NOW = "now()";

    /**
     * ClickHouse 空值替换函数
     */
    private static final String FUNCTION_IFNULL = "ifNull";

    /**
     * 任意位置的 {@code AUTO_INCREMENT} 关键字（含表尾 {@code AUTO_INCREMENT=100} 形式）
     */
    private static final Pattern AUTO_INCREMENT_TOKEN = Pattern.compile(
            "\\s*\\bAUTO_INCREMENT\\b(\\s*=\\s*\\d+)?", Pattern.CASE_INSENSITIVE);

    /**
     * {@code ON UPDATE CURRENT_TIMESTAMP} 列级选项（MySQL 专属，ClickHouse 不支持）
     */
    private static final Pattern ON_UPDATE_CURRENT_TIMESTAMP = Pattern.compile(
            "\\s+ON\\s+UPDATE\\s+CURRENT_TIMESTAMP(?:\\s*\\(\\s*\\d*\\s*\\))?", Pattern.CASE_INSENSITIVE);

    /**
     * 整型显示宽度（{@code TINYINT(1)}、{@code INT(11)}、{@code BIGINT(20)} 等）。
     * ClickHouse 的 {@code Int8/Int16/Int32/Int64} 不接受括号参数，先剥离再映射
     */
    private static final Pattern INTEGER_DISPLAY_WIDTH = Pattern.compile(
            "\\b(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT)\\s*\\(\\s*\\d+\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * 浮点精度括号（{@code FLOAT(10,2)}）。ClickHouse 浮点类型不接收参数
     */
    private static final Pattern FLOAT_PRECISION = Pattern.compile(
            "\\b(DOUBLE|FLOAT|REAL)\\s*\\(\\s*\\d+\\s*(?:,\\s*\\d+\\s*)?\\)", Pattern.CASE_INSENSITIVE);

    /**
     * 时间精度括号（{@code DATETIME(3)}、{@code TIMESTAMP(6)}）
     */
    private static final Pattern TIME_PRECISION = Pattern.compile(
            "\\b(DATETIME|TIMESTAMP)\\s*\\(\\s*\\d*\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * {@code TINYINT} → {@code Int8}
     */
    private static final Pattern TYPE_TINYINT = Pattern.compile("\\bTINYINT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code SMALLINT} → {@code Int16}
     */
    private static final Pattern TYPE_SMALLINT = Pattern.compile("\\bSMALLINT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code MEDIUMINT} → {@code Int32}
     */
    private static final Pattern TYPE_MEDIUMINT = Pattern.compile("\\bMEDIUMINT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code INT} / {@code INTEGER} → {@code Int32}
     */
    private static final Pattern TYPE_INT = Pattern.compile("\\bINT(?:EGER)?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code BIGINT} → {@code Int64}
     */
    private static final Pattern TYPE_BIGINT = Pattern.compile("\\bBIGINT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code DATETIME} / {@code TIMESTAMP} → {@code DateTime}
     */
    private static final Pattern TYPE_DATETIME = Pattern.compile(
            "\\b(DATETIME|TIMESTAMP)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code TINYTEXT} → {@code String}
     */
    private static final Pattern TYPE_TINYTEXT = Pattern.compile("\\bTINYTEXT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code MEDIUMTEXT} → {@code String}
     */
    private static final Pattern TYPE_MEDIUMTEXT = Pattern.compile("\\bMEDIUMTEXT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code LONGTEXT} → {@code String}
     */
    private static final Pattern TYPE_LONGTEXT = Pattern.compile("\\bLONGTEXT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code TEXT} → {@code String}
     */
    private static final Pattern TYPE_TEXT = Pattern.compile("\\bTEXT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code TINYBLOB} / {@code SMALLBLOB} / {@code MEDIUMBLOB} / {@code LONGBLOB} /
     * {@code BLOB} → {@code String}
     */
    private static final Pattern TYPE_BLOB = Pattern.compile(
            "\\b(?:TINY|SMALL|MEDIUM|LONG)?BLOB\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code BINARY} / {@code VARBINARY} → {@code String}
     */
    private static final Pattern TYPE_BINARY = Pattern.compile(
            "\\b(?:VAR)?BINARY\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code JSON} → {@code String}（ClickHouse 的 JSON 类型为实验特性，需开启
     * {@code allow_experimental_json_type}，故统一落到 String）
     */
    private static final Pattern TYPE_JSON = Pattern.compile("\\bJSON\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code DOUBLE} / {@code REAL} → {@code Float64}
     */
    private static final Pattern TYPE_DOUBLE = Pattern.compile("\\b(?:DOUBLE|REAL)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code FLOAT} → {@code Float64}
     */
    private static final Pattern TYPE_FLOAT_PATTERN = Pattern.compile("\\bFLOAT\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code NOW()} → {@code now()}
     */
    private static final Pattern FUNCTION_NOW_PATTERN = Pattern.compile(
            "\\bNOW\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * {@code IFNULL(} → {@code ifNull(}
     */
    private static final Pattern FUNCTION_IFNULL_PATTERN = Pattern.compile(
            "\\bIFNULL\\s*\\(", Pattern.CASE_INSENSITIVE);

    /**
     * {@code ALTER TABLE t ADD COLUMN [IF NOT EXISTS] c ...}。
     * ClickHouse 不接受 {@code IF NOT EXISTS}，且无 {@code information_schema} 可做元数据守卫，
     * 命中即整条跳过
     */
    private static final Pattern ADD_COLUMN_IF_NOT_EXISTS = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+`?[^\\s`]+`?\\s+ADD\\s+(?:COLUMN\\s+)?IF\\s+NOT\\s+EXISTS\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public boolean supports(String protocol) {
        return PROTOCOL.equalsIgnoreCase(protocol);
    }

    @Override
    public String convert(String statement, String protocol) {
        if (statement != null && ADD_COLUMN_IF_NOT_EXISTS.matcher(statement).find()) {
            return null;
        }
        String converted = super.convert(statement, protocol);
        if (converted == null || converted.isBlank()) {
            return converted;
        }
        converted = AUTO_INCREMENT_TOKEN.matcher(converted).replaceAll("");
        converted = ON_UPDATE_CURRENT_TIMESTAMP.matcher(converted).replaceAll("");
        converted = mapType(converted);
        converted = FUNCTION_NOW_PATTERN.matcher(converted).replaceAll(FUNCTION_NOW);
        return FUNCTION_IFNULL_PATTERN.matcher(converted).replaceAll(FUNCTION_IFNULL + "(");
    }

    /**
     * MySQL 类型体系 → ClickHouse 类型体系映射。
     *
     * <p>执行顺序：先剥离整型显示宽度、浮点精度括号、时间精度括号（ClickHouse 的
     * {@code Int32} / {@code Float64} / {@code DateTime} 均不接受参数），
     * 再按「长前缀优先」顺序替换类型名。</p>
     *
     * @param sql 待转换语句
     * @return 类型已映射的语句
     */
    private String mapType(String sql) {
        String result = INTEGER_DISPLAY_WIDTH.matcher(sql).replaceAll("$1");
        result = FLOAT_PRECISION.matcher(result).replaceAll("$1");
        result = TIME_PRECISION.matcher(result).replaceAll("$1");
        result = TYPE_TINYINT.matcher(result).replaceAll("Int8");
        result = TYPE_SMALLINT.matcher(result).replaceAll("Int16");
        result = TYPE_MEDIUMINT.matcher(result).replaceAll("Int32");
        result = TYPE_INT.matcher(result).replaceAll("Int32");
        result = TYPE_BIGINT.matcher(result).replaceAll("Int64");
        result = TYPE_DATETIME.matcher(result).replaceAll(TYPE_DATETIME_TIME);
        result = TYPE_TINYTEXT.matcher(result).replaceAll(TYPE_STRING);
        result = TYPE_MEDIUMTEXT.matcher(result).replaceAll(TYPE_STRING);
        result = TYPE_LONGTEXT.matcher(result).replaceAll(TYPE_STRING);
        result = TYPE_TEXT.matcher(result).replaceAll(TYPE_STRING);
        result = TYPE_BLOB.matcher(result).replaceAll(TYPE_STRING);
        result = TYPE_BINARY.matcher(result).replaceAll(TYPE_STRING);
        result = TYPE_JSON.matcher(result).replaceAll(TYPE_STRING);
        result = TYPE_DOUBLE.matcher(result).replaceAll(TYPE_FLOAT);
        return TYPE_FLOAT_PATTERN.matcher(result).replaceAll(TYPE_FLOAT);
    }
}
