package com.chua.calcite.support.datasource;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.datasource.support.datasource.DataScheme;
import com.chua.datasource.support.datasource.DataTable;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 引擎数据方案实现类，将 {@link Engine} 实例适配为 {@link DataScheme}。
 * <p>
 * 每个注册的实体类自动映射为一张 {@link DataTable}，
 * 底层数据由 Engine 提供的 Lambda 查询能力驱动。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class EngineDataSchema implements DataScheme {

    /**
     * 方案名称
     */
    private final String name;

    /**
     * 引擎实例
     */
    private final Engine engine;

    /**
     * 数据表列表
     */
    private final List<DataTable> tables;

    /**
     * 构造函数。
     *
     * @param name   方案名称
     * @param engine 引擎实例
     */
    public EngineDataSchema(String name, Engine engine) {
        this.name = name;
        this.engine = engine;
        this.tables = new ArrayList<>();
    }

    /**
     * 注册实体类为虚拟表，表名使用实体类简单名称。
     *
     * @param entityClass 实体类类型
     * @return 当前实例
     */
    public EngineDataSchema addEntity(Class<?> entityClass) {
        return addEntity(entityClass.getSimpleName(), entityClass);
    }

    /**
     * 注册实体类为指定表名的虚拟表。
     *
     * @param tableName   表名
     * @param entityClass 实体类类型
     * @return 当前实例
     */
    public EngineDataSchema addEntity(String tableName, Class<?> entityClass) {
        String key = tableName != null ? tableName : entityClass.getSimpleName();
        tables.add(new SourceDataTable(key, engine, entityClass));
        log.debug("[calcite] EngineDataSchema [{}] 注册表: {} -> {}", name, key, entityClass.getSimpleName());
        return this;
    }

    /**
     * 批量注册多个实体类。
     *
     * @param entityClasses 实体类列表
     * @return 当前实例
     */
    public EngineDataSchema addEntities(Class<?>... entityClasses) {
        if (entityClasses != null) {
            for (Class<?> ec : entityClasses) {
                addEntity(ec);
            }
        }
        return this;
    }

    /**
     * 底层引擎（供 SQL UPDATE 路由等使用）。
     */
    public Engine getEngine() {
        return engine;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public List<String> getTableNames() {
        return tables.stream().map(DataTable::getName).collect(Collectors.toList());
    }

    @Override
    public DataTable getTable(String name) {
        for (DataTable t : tables) {
            if (t.getName().equals(name)) {
                return t;
            }
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        engine.close();
    }
}
