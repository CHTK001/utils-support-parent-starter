package com.chua.calcite.support.datasource;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.datasource.support.engine.FileEngine;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Engine / FileEngine → Calcite 统一 {@link DataSource} 工厂。
 *
 * <pre>{@code
 * FileEngine engine = new FileEngine().load("user", "users.csv");
 * DataSource ds = EngineDataSourceFactory.from(engine, "file", User.class);
 * // SELECT * FROM `file`.`user` WHERE `age` > 25
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class EngineDataSourceFactory {

    /**
     * 默认 schema 名称
     */
    private static final String DEFAULT_SCHEMA = "file";

    /**
     * 私有构造，禁止实例化。
     */
    private EngineDataSourceFactory() {
    }

    /**
     * 将 Engine 注册为 schema，实体类映射为表（表名：驼峰转下划线，如 User→user）。
     *
     * @param engine        引擎
     * @param schemaName    schema 名
     * @param entityClasses 实体类
     * @return 统一 DataSource
     */
    public static DataSource from(Engine engine, String schemaName, Class<?>... entityClasses) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(schemaName, "schemaName");
        EngineDataSchema schema = new EngineDataSchema(schemaName, engine);
        if (entityClasses != null) {
            for (Class<?> entityClass : entityClasses) {
                if (entityClass == null) {
                    continue;
                }
                schema.addEntity(toTableName(entityClass), entityClass);
            }
        }
        DataSource raw = CalciteDataSourceCreator.newCreator()
                .addScheme(schema)
                .create();
        // 拦截 SQL UPDATE → Engine（Calcite ModifiableTable 不支持 UPDATE）
        return new EngineAwareDataSource(raw, java.util.List.of(schema));
    }

    /**
     * FileEngine 专用快捷方法（schema 默认 {@code file}）。
     *
     * @param engine        文件引擎
     * @param entityClasses 实体类
     * @return 统一 DataSource
     */
    public static DataSource fromFile(FileEngine engine, Class<?>... entityClasses) {
        return from(engine, DEFAULT_SCHEMA, entityClasses);
    }

    /**
     * FileEngine + 自定义 schema。
     *
     * @param engine        文件引擎
     * @param schemaName    schema 名
     * @param entityClasses 实体类
     * @return 统一 DataSource
     */
    public static DataSource fromFile(FileEngine engine, String schemaName, Class<?>... entityClasses) {
        return from(engine, schemaName, entityClasses);
    }

    /**
     * 实体类简单名转下划线命名（User → user；UserOrder → user_order）。
     *
     * @param entityClass 实体类
     * @return 表名
     */
    static String toTableName(Class<?> entityClass) {
        String simpleName = entityClass.getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (char c : simpleName.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
