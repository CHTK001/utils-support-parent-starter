package com.chua.h2.support.flyway;

import com.chua.common.support.lang.datasource.flyway.DefaultScriptConverter;
import com.chua.common.support.spi.annotations.Spi;

import java.util.regex.Pattern;

/**
 * H2 迁移脚本方言转换器（{@code ScriptConverter} 的 h2 协议实现）。
 *
 * <p>H2 是本项目的默认测试库与本地开发库（{@code H2V1Dialect} 对应 H2 1.x 系列），
 * 迁移脚本在 CI 与本地默认都会先落到 H2 上执行一遍，因此 H2 的转换正确性优先级最高：
 * 一条 H2 上执行失败的语句会让整轮验证中断，而不是像其它方言那样只是运行期不兼容。</p>
 *
 * <h3>与 MySQL 源方言的具体差异</h3>
 * <p>H2 同时提供 SQL 标准模式与 MySQL 兼容模式，脚本用哪种模式执行会改变可用语法。
 * 本转换器按<b>标准模式</b>（{@code jdbc:h2:mem:...} 默认）编写，因为本地与 CI 默认不带
 * {@code ;MODE=MySQL}，这是覆盖面最广、也是最容易暴露不兼容的执行路径。</p>
 *
 * <h3>逐条规则与依据</h3>
 * <ol>
 *   <li>{@code JSON} → {@code CLOB}：<b>由 {@link DefaultScriptConverter} 的 {@code case "h2"} 完成</b>
 *       （{@code JSON} → 目标库文本类型），本类显式保留该行为、不再重复实现。
 *       依据：H2 1.x 没有 {@code JSON} 数据类型；用 {@code CLOB} 落地可完整保存 JSON 文本
 *       （改用 {@code VARCHAR} 会在超长时截断），与父类的 Oracle/SQL Server 分支保持一致</li>
 *   <li>{@code ENGINE=} / {@code AUTO_INCREMENT=} / {@code CHARSET=} / {@code COLLATE=} /
 *       内联 {@code COMMENT} / 行内 {@code KEY} / {@code UNIQUE KEY}：
 *       已由 {@link DefaultScriptConverter} 的表尾属性、行内索引、列内联注释三组规则剥离，
 *       本类不重复实现，只做确认。依据：这些全是 {@code SHOW CREATE TABLE} 导出风格的 MySQL 专属子句，
 *       H2 解析器一律报语法错误。索引改由独立的 {@code CREATE INDEX} 语句承载</li>
 *   <li>{@code ON DUPLICATE KEY UPDATE} → 整条返回 {@code null} 跳过。
 *       依据：该语法<b>只有 H2 的 MySQL 兼容模式</b>才支持，标准模式下是语法错误，
 *       而本项目 H2 连接串并不保证带 {@code ;MODE=MySQL}，因此不能假定它可用。
 *       之所以不做「改写为 {@code MERGE INTO}」：{@code MERGE} 必须显式给出
 *       {@code USING (SELECT ...)} 源与 {@code ON} 匹配条件，且 {@code WHEN NOT MATCHED THEN INSERT}
 *       的列集合必须与目标表完全一致——这些都要预知主键列与全部列，
 *       在无元数据上下文的纯文本转换里只能靠猜，属于会把「语法错误」升级成「静默写错数据」的高风险改写。
 *       跳过是保守但结果可预期的选择：{@code db/initdata} 中的 upsert 属初始化数据，
 *       缺一条不影响建表与主流程</li>
 *   <li>{@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}：<b>保持原样，不做任何改写</b>。
 *       依据：H2 原生支持 {@code ADD COLUMN IF NOT EXISTS}，这是少数「MySQL 不支持、H2 支持」的幂等写法，
 *       剥掉 {@code IF NOT EXISTS} 会让重复执行直接报错。这与 {@code MysqlScriptConverter} 的处理方向恰好相反</li>
 *   <li>类型映射：{@code DATETIME}→{@code TIMESTAMP}（H2 无独立 DATETIME 类型）；
 *       {@code MEDIUMTEXT}/{@code LONGTEXT}/{@code TINYTEXT}→{@code CLOB}（H2 标准模式下无 MySQL 文本族；
 *       H2 的 {@code CLOB} 可不带默认值，脚本里的 {@code DEFAULT NULL} 可正常保留）；
 *       <b>保持宽度会被剥离</b>：{@code TINYINT(1)} → {@code TINYINT}。H2 1.4 虽接受显示宽度，
 *       但 H2 2.x 严格模式下直接报语法错误（实测 2.4.240 报 42001）；去宽度在两代 H2 上都合法，故统一剥离；
 *       {@code DOUBLE}→{@code DOUBLE PRECISION}（{@code DOUBLE} 在 H2 中是 {@code DOUBLE PRECISION}
 *       的别名，显式写出可避免与其它方言书写不一致）。
 *       裸 {@code TEXT}→{@code CLOB}：H2 标准模式无 {@code TEXT} 别名（实测 2.4.240 报 42001），
 *       本项目 edu 等模块脚本确有裸 {@code TEXT} 列，故与 {@code MEDIUMTEXT} 等同映射</li>
 *   <li>函数映射：{@code NOW()}→{@code CURRENT_TIMESTAMP}、{@code IFNULL(}→{@code COALESCE(}、
 *       {@code RAND()}→{@code RANDOM()}。依据：{@code CURRENT_TIMESTAMP} 与 {@code COALESCE} 是标准
 *       {@code CURRENT_TIMESTAMP} 关键字与两参数空值合并；H2 的随机函数名为 {@code RANDOM()}</li>
 *   <li>反引号：<b>保留不动</b>。依据：H2 默认即接受反引号作标识符定界（标准模式下也可用），
 *       且 MySQL 兼容模式下同样支持，无需额外改写为双引号（双引号在 H2 中是「标识符」语义，
 *       改写反而会在某些配置下变成字符串字面量）</li>
 * </ol>
 *
 * <h3>扩展键与前提</h3>
 * <p>扩展键必须等于协议名（{@code @Spi("h2")} + 本模块
 * {@code META-INF/extensions/com.chua.common.support.lang.datasource.flyway.ScriptConverter}
 * 中的 {@code h2=} 行）：因为 {@code ScriptConverter} 的 {@code getExtension(protocol)}
 * 是先按协议名精确查扩展键，再退到 {@code @SpiDefault} 兜底实现；
 * 若扩展键与协议名不同（哪怕写成 SPI 名称 {@code script-converter}），
 * 就会与兜底默认实现同键互相遮蔽，本类的 H2 专属规则永远不会被命中。</p>
 *
 * <p>前提与 {@link DefaultScriptConverter} 一致：{@code CREATE TABLE} 按<b>每列一行</b>的对齐格式书写
 * （行内索引各占一行，列定义内联 {@code COMMENT} 在行尾）。
 * 本类的行级剥离与类型替换都依赖行尾锚定与单词边界，单行紧凑写法的
 * {@code CREATE TABLE} 不在转换范围内。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(value = H2ScriptConverter.PROTOCOL, order = 100)
public class H2ScriptConverter extends DefaultScriptConverter {

    /**
     * 本协议名（同时作为 SPI 扩展键，见本模块 {@code META-INF/extensions/...ScriptConverter}）
     */
    public static final String PROTOCOL = "h2";

    /**
     * MySQL {@code ON DUPLICATE KEY UPDATE}（仅 H2 的 MySQL 兼容模式支持，标准模式语法错误）。
     * 命中即整条跳过，不做 {@code MERGE INTO} 改写（理由见类注释）
     */
    private static final Pattern ON_DUPLICATE_KEY_UPDATE = Pattern.compile(
            "(?i)\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b");

    /**
     * {@code DATETIME} → {@code TIMESTAMP}（H2 标准模式无独立 DATETIME 类型）
     */
    private static final Pattern DATETIME_TYPE = Pattern.compile("(?i)\\bDATETIME\\b");

    /**
     * MySQL 文本族 → H2 {@code CLOB}。
     * 分支顺序必须长类型在前：若先匹配到 {@code TEXT}，{@code MEDIUMTEXT}/{@code LONGTEXT}/{@code TINYTEXT}
     * 会被截成 {@code MEDIOCLOB} 这类坏串。
     * 裸 {@code TEXT} 同样映射：H2 标准模式无 {@code TEXT} 别名（实测 2.4.240 报 42001），
     * 本项目脚本中确有裸 {@code TEXT} 列，故一并映射，不做「脚本里没出现就不管」的猜测
     */
    private static final Pattern TEXT_TYPE = Pattern.compile(
            "(?i)\\b(?:MEDIUMTEXT|LONGTEXT|TINYTEXT|TEXT)\\b");

    /**
     * {@code DOUBLE} → {@code DOUBLE PRECISION}。
     * 负向断言保证已写成 {@code DOUBLE PRECISION} 的语句不被二次替换成
     * {@code DOUBLE PRECISION PRECISION}
     */
    private static final Pattern DOUBLE_TYPE = Pattern.compile(
            "(?i)\\bDOUBLE\\b(?!\\s+PRECISION)");

    /**
     * {@code TINYINT(1)} → {@code TINYINT}：H2 1.4 接受显示宽度，但 H2 2.x 严格模式下
     * {@code TINYINT(1)} 直接报语法错误（实测 {@code com.h2database:h2:2.4.240} 报 42001）。
     * 去掉显示宽度在 1.x 与 2.x 上都合法，因此统一剥离，不按 H2 版本分叉
     */
    private static final Pattern TINYINT_DISPLAY_WIDTH = Pattern.compile("(?i)\\bTINYINT\\s*\\(\\s*1\\s*\\)");

    /**
     * MySQL {@code INSERT IGNORE INTO ...} → H2 {@code INSERT INTO ...}（仅剥离 H2 不支持的 {@code IGNORE} 关键字）。
     * <p>依据（实测 {@code com.h2database:h2:2.4.240} 标准模式）：{@code INSERT IGNORE}、
     * {@code INSERT OR IGNORE}、{@code ON CONFLICT DO NOTHING}、{@code ON DUPLICATE KEY UPDATE}
     * 全部报 42000/42001 语法错误。</p>
     * <p><b>为何不用 {@code MERGE} 换取幂等</b>：H2 的 {@code MERGE} 只有在主键列出现在 INSERT 的列清单里时
     * 才能免写 {@code KEY} 子句；本项目的初始化数据大量依赖自增主键、不显式列出主键列，此时 {@code MERGE}
     * 会报 90081（主键列含空值）。补 {@code KEY (...)} 又需要主键元数据，而本转换器是无元数据的纯文本转换，
     * 拿不到、也不允许猜。因此这里选择可预测的「能执行」而非「看似幂等」。</p>
     * <p><b>代价与前提</b>：剥离 {@code IGNORE} 后，重复执行同一份初始化数据会因主键冲突而报错。
     * 这是可接受的，因为 Flyway 按版本号每份脚本只执行一次（{@code migrateInitData} 记录进
     * {@code sys_database_version} 后不再重跑）。若某个库需要「可重复执行的种子数据」，
     * 应在脚本中显式写出主键列，此时方可安全改用 {@code MERGE}。</p>
     */
    private static final Pattern INSERT_IGNORE = Pattern.compile(
            "(?i)^\\s*INSERT\\s+IGNORE(?=\\s+INTO\\b)");

    /**
     * MySQL {@code NOW()} → 标准 {@code CURRENT_TIMESTAMP}（无括号关键字）
     */
    private static final Pattern NOW_FUNCTION = Pattern.compile("(?i)\\bNOW\\s*\\(\\s*\\)");

    /**
     * MySQL {@code IFNULL(} → 标准 {@code COALESCE(}
     */
    private static final Pattern IFNULL_FUNCTION = Pattern.compile("(?i)\\bIFNULL\\s*\\(");

    /**
     * MySQL {@code RAND()} → H2 {@code RANDOM()}。
     * {@code \b} 与紧跟的 {@code \(} 共同保证不会命中 {@code RANDOM(} 自身
     */
    private static final Pattern RAND_FUNCTION = Pattern.compile("(?i)\\bRAND\\s*\\(\\s*\\)");

    @Override
    public boolean supports(String protocol) {
        return PROTOCOL.equalsIgnoreCase(protocol);
    }

    @Override
    public String convert(String statement, String protocol) {
        // 先走父类：h2 分支已把 JSON 映射为 CLOB，并剥离表尾 MySQL 属性、行内 KEY、列内联 COMMENT
        String converted = super.convert(statement, protocol);
        if (converted == null || converted.isBlank()) {
            return converted;
        }
        // ON DUPLICATE KEY UPDATE 仅 MySQL 兼容模式支持，标准模式一律跳过而非高风险改写
        if (ON_DUPLICATE_KEY_UPDATE.matcher(converted).find()) {
            return null;
        }
        // INSERT IGNORE -> INSERT（仅剥离 H2 不支持的 IGNORE，依据与代价见 INSERT_IGNORE 注释）
        converted = INSERT_IGNORE.matcher(converted).replaceFirst("INSERT");
        // 类型映射；TINYINT 去掉显示宽度（H2 2.x 拒绝 TINYINT(1)），ADD COLUMN IF NOT EXISTS 保持原样（H2 原生支持）
        converted = DATETIME_TYPE.matcher(converted).replaceAll("TIMESTAMP");
        converted = TEXT_TYPE.matcher(converted).replaceAll("CLOB");
        converted = TINYINT_DISPLAY_WIDTH.matcher(converted).replaceAll("TINYINT");
        converted = DOUBLE_TYPE.matcher(converted).replaceAll("DOUBLE PRECISION");
        // 函数映射
        converted = NOW_FUNCTION.matcher(converted).replaceAll("CURRENT_TIMESTAMP");
        converted = IFNULL_FUNCTION.matcher(converted).replaceAll("COALESCE(");
        converted = RAND_FUNCTION.matcher(converted).replaceAll("RANDOM()");
        return converted;
    }
}
