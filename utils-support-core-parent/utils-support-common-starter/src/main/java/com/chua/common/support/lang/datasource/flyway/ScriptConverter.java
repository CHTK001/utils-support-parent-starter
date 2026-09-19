package com.chua.common.support.lang.datasource.flyway;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * 迁移脚本方言转换器 SPI。
 *
 * <p>针对特定目标数据库对迁移脚本语句做方言兼容转换（MySQL → H2/PostgreSQL/Oracle/SQL Server 等），
 * 解决"一套 MySQL 风格 DDL 脚本在多库运行"的兼容问题。转换在语句拆分之后、
 * JDBC 执行之前进行，与 {@link Flyway} 的版本记录/幂等机制正交。</p>
 *
 * <h3>内置能力（DefaultScriptConverter 提供，无需额外实现）</h3>
 * <ul>
 *   <li>剥离 MySQL 专属前导语句：{@code SET NAMES}、{@code SET FOREIGN_KEY_CHECKS}</li>
 *   <li>剥离 CREATE TABLE 尾部 MySQL 专属属性：{@code ENGINE=InnoDB}/{@code CHARSET=utf8mb4}/{@code ROW_FORMAT}/{@code AUTO_INCREMENT} 等</li>
 *   <li>剥离内联 {@code COMMENT 'xxx'}（MySQL 专属；其他方言可用独立 COMMENT ON 语句替代）</li>
 *   <li>剥离 {@code USING BTREE}、{@code KEY idx ON} 行内索引声明（改由 {@code CREATE INDEX} 承载）</li>
 *   <li>函数方言映射：{@code NOW()→CURRENT_TIMESTAMP}、{@code REPLACE(UUID(),'-','')→SYS_GUID()} 等</li>
 *   <li>整语句透传：{@code PREPARE/EXECUTE/DEALLOCATE}/{@code SET @var}（MySQL 动态 SQL 段）
 *       在目标库不支持时整条跳过，避免半执行</li>
 * </ul>
 *
 * <h3>扩展方式</h3>
 * <ol>
 *   <li>实现本接口（可继承 {@code DefaultScriptConverter} 只做增量转换）</li>
 *   <li>类上标注 {@code @Spi(value = ScriptConverter.SPI_NAME, order = 100)}，
 *       {@code supports(protocol)} 返回目标库协议名（如 {@code h2}、{@code postgresql}）</li>
 *   <li>默认实现（order=0，{@code @SpiDefault}）支持全部协议，作为兜底</li>
 * </ol>
 *
 * <p>执行链（由 {@code DataSourceFlyway} / 内置 Populator 驱动）：</p>
 * <pre>
 *   原始脚本 --拆分--> 语句列表 --ScriptConverter.convert(protocol)--> 转换后语句列表 --JDBC--> 目标库
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(ScriptConverter.SPI_NAME)
public interface ScriptConverter {

    /** SPI 名称（{@code @Spi} 扩展键） */
    String SPI_NAME = "script-converter";

    /**
    * 判断当前转换器是否支持目标数据库协议。
    *
    * @param protocol 数据库协议名（如 {@code mysql}、{@code h2}、{@code postgresql}、{@code oracle}、{@code sqlserver}）
    * @return true 表示该转换器可对目标协议做方言转换
    */
    boolean supports(String protocol);

    /**
     * 对单条语句做方言转换。
     *
     * <p>返回 {@code null} 表示该语句在目标库不可执行且无等价转换，应整条跳过
     * （典型场景：MySQL 动态 SQL 段 {@code PREPARE ... FROM @var} 在 H2/PG 下无法执行，
     * 而其前置的 {@code ALTER TABLE} 已在全量建表中覆盖）。</p>
     *
     * @param statement 单条语句（已按分号拆分，不含分隔符）
     * @param protocol  目标数据库协议名
     * @return 转换后的语句；null 表示跳过
     */
    String convert(String statement, String protocol);
    /**
     * 对语句列表做批量方言转换。
     * <p>默认实现逐条调用 {@link #convert(String, String)}，过滤 null 后返回。</p>
     *
     * @param statements 拆分后的语句列表
     * @param protocol   目标数据库协议名
     * @return 转换后的可执行语句列表（null 条目已过滤）
     */
    default List<String> convertAll(List<String> statements, String protocol) {
        return statements.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> convert(s, protocol))
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }

    /**
     * 通过 SPI 获取支持指定协议的转换器。
     * <p>按 SPI 注册顺序遍历扩展，匹配 {@link #supports(String)} 返回的第一项；
     * 全部不支持时返回默认兜底实现（{@code @SpiDefault}，支持全部协议）。</p>
     *
     * @param protocol 目标数据库协议名
     * @return 转换器实例，无法获取时返回 null
     */
    static ScriptConverter getExtension(String protocol) {
        var provider = ServiceProvider.of(ScriptConverter.class);
        for (ScriptConverter c : provider.collect()) {
            if (c != null && c.supports(protocol)) {
                return c;
            }
        }
        // 兜底：默认实现（@SpiDefault, order=0）支持全部协议
        ScriptConverter def = provider.getDefault();
        return def != null && def.supports(protocol) ? def : null;
    }
}
