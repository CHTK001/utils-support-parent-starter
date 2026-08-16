package com.chua.parquet.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.engine.MemoryWhereParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Parquet 列式存储引擎实现，提供基于内存的数据过滤查询能力。
 * <p>
 * 支持通过 {@link #store} 注入数据，利用 {@link MemoryWhereParser}
 * 解析 SQL WHERE 条件进行内存过滤，并支持基于内存的 UPDATE/DELETE 操作。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("parquet")
public class ParquetEngine extends AbstractEngine {

    /**
     * 将数据按名称存储到引擎中。
     *
     * @param name 数据存储的名称（表名）
     * @param data 要存储的数据列表
     * @param <T>  数据类型
     * @return 当前引擎实例
     */
    public <T> ParquetEngine store(String name, List<T> data) {
        dataStores.put(name, new ArrayList<>(data));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 将数据存储到默认位置。
     *
     * @param data 要存储的数据列表
     * @param <T>  数据类型
     * @return 当前引擎实例
     */
    public <T> ParquetEngine store(List<T> data) {
        return store("default", data);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> List<T> executeNewQuery(
            String where, Object[] params, Class<T> entityClass) {
        List<T> data = getData(entityClass);
        if (data.isEmpty()) {
            return data;
        }
        if (where == null || where.trim().isEmpty()) {
            return data;
        }
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> paramList = (params != null)
                ? Arrays.asList(params)
                : Collections.emptyList();
        var predicate = parser.parse(where, paramList);
        return data.stream().filter(predicate).toList();
    }
}