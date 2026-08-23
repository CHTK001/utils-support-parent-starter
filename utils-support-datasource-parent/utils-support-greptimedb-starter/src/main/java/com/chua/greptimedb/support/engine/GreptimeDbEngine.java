package com.chua.greptimedb.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.engine.MemoryWhereParser;
import com.chua.greptimedb.support.client.GreptimeDbClient;
import com.chua.greptimedb.support.datasource.GreptimeDbEngineDataSource;
import io.greptime.GreptimeDB;
import io.greptime.models.Err;
import io.greptime.models.Result;
import io.greptime.models.Table;
import io.greptime.models.WriteOk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * GreptimeDB 时序数据库引擎实现。
 * <p>
 * 基于官方 gRPC Ingester SDK 提供高性能写入能力，并继承 {@link AbstractEngine} 的内存查询能力，
 * 通过 SPI 注册为 {@code "greptimedb"}。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("greptimedb")
public class GreptimeDbEngine extends AbstractEngine {

    /**
     * 默认存储名称
     */
    private static final String DEFAULT_NAME = "default";

    /**
     * 将内存数据按名称存储到引擎中，支持 Lambda 内存查询。
     *
     * @param name 数据存储的名称（表名）
     * @param data 要存储的数据列表
     * @param <T>  数据类型
     * @return 当前引擎实例
     */
    public <T> GreptimeDbEngine store(String name, List<T> data) {
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
    public <T> GreptimeDbEngine store(List<T> data) {
        return store(DEFAULT_NAME, data);
    }

    /**
     * 添加数据源（GreptimeDB 客户端或连接串）。
     *
     * @param name       数据源名称
     * @param dataSource 数据源封装
     * @param <T>        底层类型
     * @return this
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        Object source = dataSource.getSource();
        GreptimeDbEngineDataSource wrapped;
        if (source instanceof GreptimeDB client) {
            wrapped = new GreptimeDbEngineDataSource(name, dataSource.url(),
                    dataSource.username(), dataSource.password(), dataSource.database(), client);
        } else if (source instanceof String url) {
            GreptimeDB client = GreptimeDbClient.create(url, dataSource.database(),
                    dataSource.username(), dataSource.password());
            wrapped = new GreptimeDbEngineDataSource(name, url,
                    dataSource.username(), dataSource.password(), dataSource.database(), client);
        } else {
            throw new IllegalArgumentException("GreptimeDbEngine 仅支持 GreptimeDB 客户端或 URL 字符串");
        }
        dataSources.put(name, (EngineDataSource<Object>) (Object) wrapped);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 便捷添加数据源（直接创建客户端）。
     *
     * @param name      数据源名称
     * @param endpoint  GreptimeDB gRPC 端点
     * @param database  数据库名
     * @param username  用户名（为空表示无鉴权）
     * @param password  密码
     * @return this
     */
    public GreptimeDbEngine addDataSource(String name, String endpoint,
                                          String database, String username, String password) {
        GreptimeDB client = GreptimeDbClient.create(endpoint, database, username, password);
        GreptimeDbEngineDataSource wrapped = new GreptimeDbEngineDataSource(
                name, endpoint, username, password, database, client);
        dataSources.put(name, (EngineDataSource<Object>) (Object) wrapped);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 获取默认 GreptimeDB 客户端。
     *
     * @return GreptimeDB 客户端
     */
    public GreptimeDB client() {
        GreptimeDbEngineDataSource ds = (GreptimeDbEngineDataSource) (Object) dataSources.get(defaultDataSourceName);
        if (ds == null) {
            throw new IllegalStateException("未配置 GreptimeDB 数据源");
        }
        return ds.getSource();
    }

    /**
     * 写入一张表到 GreptimeDB（gRPC 异步写入）。
     *
     * @param table 表数据（由 SDK {@link Table} 构建）
     * @return 写入结果 Future
     */
    public CompletableFuture<Result<WriteOk, Err>> write(Table table) {
        return client().write(table);
    }

    /**
     * 批量写入多张表到 GreptimeDB（gRPC 异步写入）。
     *
     * @param tables 表数据
     * @return 写入结果 Future
     */
    public CompletableFuture<Result<WriteOk, Err>> write(Table... tables) {
        return client().write(tables);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 执行内存查询（GreptimeDB 为写入型时序库，读取走内存过滤）。
     */
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass) {
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
