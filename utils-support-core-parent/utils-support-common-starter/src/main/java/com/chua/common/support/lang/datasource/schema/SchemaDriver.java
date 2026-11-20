package com.chua.common.support.lang.datasource.schema;

import com.chua.common.support.spi.annotations.Spi;

/**
 * 数据库结构驱动 SPI 扩展点。
 *
 * <p>决定「数据库表结构如何初始化」的可插拔策略。数据源脚本装配器
 * （{@code spring-support-datasource-starter}）按配置的驱动模式解析本扩展：
 * </p>
 * <ul>
 *   <li>{@link #SCRIPT} — 脚本驱动（默认）：按 {@code V{版本}__{描述}.sql} 规范执行
 *       classpath 下的 SQL 脚本，由 spring 侧内置链路承载，不依赖本 SPI；</li>
 *   <li>{@link #ENTITY} — 实体驱动：扫描业务实体注解，按实体定义生成并执行
 *       {@code CREATE TABLE IF NOT EXISTS}，由 {@code EntitySchemaDriver} 提供。</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * SchemaDriver driver = ServiceProvider.of(SchemaDriver.class)
 *         .getExtension(SchemaDriver.ENTITY);
 * int tables = driver.migrate(new SchemaDriverContext()
 *         .setDataSource(dataSource)
 *         .setProtocol("mysql")
 *         .setEntityClasses(entityClasses));
 * }</pre>
 *
 * <p>注册文件：{@code META-INF/extensions/com.chua.common.support.lang.datasource.schema.SchemaDriver}，
 * 内容格式：{@code 别名=实现类全限定名}（如 {@code entity=...EntitySchemaDriver}）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Spi(SchemaDriver.SPI_NAME)
public interface SchemaDriver {

    /**
     * 结构驱动 SPI 扩展名。
     */
    String SPI_NAME = "schema-driver";

    /**
     * 脚本驱动模式：执行 classpath 下的版本化 SQL 脚本（默认模式）。
     */
    String SCRIPT = "script";

    /**
     * 实体驱动模式：按实体注解生成建表 DDL 并执行。
     */
    String ENTITY = "entity";

    /**
     * 驱动模式标识，与 {@code META-INF/extensions} 注册别名一致。
     *
     * @return 模式标识（如 {@link #ENTITY}）
     */
    String name();

    /**
     * 执行结构初始化。
     *
     * <p>实现必须幂等：重复调用不应重复创建已存在的表，也不应破坏既有数据。
     * 推荐使用 {@code CREATE TABLE IF NOT EXISTS}，或在执行前检查表是否已存在。</p>
     *
     * @param context 驱动上下文（数据源、方言协议、实体类列表等）
     * @return 本次实际执行的 DDL 数量
     */
    int migrate(SchemaDriverContext context);
}
