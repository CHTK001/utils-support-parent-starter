package com.chua.influxdb.support.engine;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.engine.MemoryWhereParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * InfluxDB 数据源引擎实现，提供基于内存的数据过滤查询能力。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("influxdb")
public class InfluxDbEngine extends AbstractEngine {

    /**
    * 默认存储名称
    */
    private static final String DEFAULT_NAME = "default";

    /**
    * 将内存数据按名称存储到引擎中。
    *
    * @param name 数据存储的名称（表名）
    * @param data 要存储的数据列表
    * @param <T>  数据类型
    * @return 当前引擎实例
    */
    public <T> InfluxDbEngine store(String name, List<T> data) {
        dataStores.put(name, new ArrayList<>(data));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
    * 将内存数据存储到默认位置。
    *
    * @param data 要存储的数据列表
    * @param <T>  数据类型
    * @return 当前引擎实例
    */
    public <T> InfluxDbEngine store(List<T> data) {
        return store(DEFAULT_NAME, data);
    }

    /**
    * 执行内存过滤查询。
    *
    * @param where       WHERE 条件
    * @param params      参数数组
    * @param entityClass 实体类
    * @param limit       返回条数上限，0 或负数表示不限
    * @param offset      跳过的条数，0 表示不跳过
    * @param <T>         数据类型
    * @return 过滤并分页后的数据
    */
    @Override
    protected <T> List<T> executeNewQuery(
            String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        List<T> data = getData(entityClass);
        if (data.isEmpty()) {
            return data;
        }
        List<Object> paramList = (params != null)
                ? Arrays.asList(params)
                : Collections.emptyList();
        java.util.stream.Stream<T> stream;
        if (where == null || where.trim().isEmpty()) {
            stream = data.stream();
        } else {
            MemoryWhereParser parser = new MemoryWhereParser();
            var predicate = parser.parse(where, paramList);
            stream = data.stream().filter(predicate);
        }
        if (offset > 0) {
            stream = stream.skip(offset);
        }
        if (limit > 0) {
            stream = stream.limit(limit);
        }
        return stream.toList();
    }
}
