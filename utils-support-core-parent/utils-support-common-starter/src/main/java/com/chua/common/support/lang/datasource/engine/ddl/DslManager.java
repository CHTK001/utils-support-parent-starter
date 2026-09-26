package com.chua.common.support.lang.datasource.engine.ddl;

import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * DDL / DSL 管理器 SPI 接口，提供表结构读取与 DDL 语句生成能力。
 *
 * <p><b>解析方式</b>：由 {@code Engine#ddl()} 统一解析，调用方无需手工
 * {@code new} 实现类。解析顺序：</p>
 * <ol>
 *   <li>取默认数据源 {@link com.chua.common.support.lang.datasource.dialect.Dialect#protocol()}
 *       作为扩展键（如 {@code mysql}、{@code postgresql}）</li>
 *   <li>未命中时回退到通用别名 {@link #DEFAULT_ALIAS}</li>
 * </ol>
 *
 * <p><b>参数注入</b>：解析成功后，框架会自动向实现注入上下文参数，
 * 实现方按需实现对应感知接口即可，无需调用方手工 setter：</p>
 * <ul>
 *   <li>{@code com.chua.datasource.support.user.DataSourceAware} — 注入 JDBC {@code DataSource}</li>
 *   <li>{@link DialectAware} — 注入当前方言（省去自行从 JDBC URL 嗅探方言的逻辑）</li>
 * </ul>
 *
 * <p>实现类必须提供<b>无参构造</b>：框架以无参方式实例化 SPI 实现，
 * 上下文全部通过上面的感知接口注入，不走构造参数
 * （传入构造参数会让 SPI 按参数个数挑构造器，实现类一旦有多个构造器就可能选错签名）。</p>
 *
 * <p>注册文件：{@code META-INF/extensions/com.chua.common.support.lang.datasource.engine.ddl.DslManager}，
 * 内容格式：{@code 别名=实现类全限定名}，例如：</p>
 * <pre>{@code
 * mysql=com.chua.mysql.support.ddl.MysqlDslManager
 * default=com.chua.datasource.support.ddl.DefaultDslManager
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(DslManager.SPI_NAME)
public interface DslManager {

    /**
     * DDL 管理器 SPI 扩展名。
     */
    String SPI_NAME = "dsl-manager";

    /**
     * 通用兜底别名：按方言协议未命中专属实现时使用。
     */
    String DEFAULT_ALIAS = "default";

    /**
     * 获取表定义。
     *
     * @param catalogName catalog 名称
     * @param schemaName  模式 名称
     * @param tableName   表名
     * @return 表定义，不存在返回 空
     */
    TableDef getTable(String catalogName, String schemaName, String tableName);

    /**
     * 生成建表 DDL。
     *
     * @param catalogName catalog 名称
     * @param schemaName  模式 名称
     * @param tableName   表名
     * @return CREATE TABLE SQL
     */
    String createTableDDL(String catalogName, String schemaName, String tableName);

    /**
     * 生成重命名表 DDL。
     *
     * @param schemaName   模式 名称
     * @param oldTableName 原表名
     * @param newTableName 新表名
     * @return RENAME TABLE SQL
     */
    String renameTable(String schemaName, String oldTableName, String newTableName);

    /**
     * 生成复制表结构 DDL。
     *
     * @param schemaName      模式 名称
     * @param sourceTableName 源表名
     * @param targetTableName 目标表名
     * @return 复制结构 SQL
     */
    String copyTableStructure(String schemaName, String sourceTableName, String targetTableName);

    /**
     * 列出表定义。
     *
     * @param catalogName catalog 名称
     * @param schemaName  模式 名称
     * @return 表定义列表
     */
    List<TableDef> listTables(String catalogName, String schemaName);

    /**
     * 管理器类型标识。
     *
     * @return 类型名称
     */
    String type();

    /**
     * 校验必需参数是否已由框架注入。
     *
     * <p>默认放行：并非所有实现都依赖注入参数（如基于搜索引擎客户端的实现）。
     * 依赖 {@code DataSource} 或 {@code Dialect} 的实现应覆盖本方法，
     * 在参数缺失时抛出可定位异常，避免后续出现难以追踪的 NPE。</p>
     *
     * @throws IllegalStateException 必需参数未注入时抛出
     */
    default void requireContext() {
    }
}
