package com.chua.nitrite.support.engine;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.engine.MemoryWhereParser;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.mvstore.MVStoreModule;
import org.dizitart.no2.NitriteBuilder;
import org.dizitart.no2.document.Document;
import org.dizitart.no2.repository.ObjectRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nitrite 文档数据库引擎，基于内存过滤和文件存储。
 *
 * @author CH
 * @since 2026/07/31
 */
@Spi("nitrite")
public class NitriteEngine extends AbstractEngine {

    private final ConcurrentHashMap<String, Nitrite> databases = new ConcurrentHashMap<>();

    /**
     * 添加一个 Nitrite 文件型数据源。
     *
     * @param name 数据源名称
     * @param filePath 数据库文件路径
     * @return this
     */
    public NitriteEngine addDataSource(String name, String filePath) {
        MVStoreModule storeModule = MVStoreModule.withConfig()
                .filePath(filePath)
                .build();
        NitriteBuilder builder = Nitrite.builder()
                .loadModule(storeModule);
        Nitrite nitrite = builder.openOrCreate();
        databases.put(name, nitrite);
        return (NitriteEngine) super.addDataSource(name, new EngineDataSource<Object>() {
            @Override
            public String name() { return name; }
            @Override
            public Object getSource() { return nitrite; }
            @Override
            public <R> R getSource(Class<R> type) { return type.cast(nitrite); }
            @Override
            public EngineDataSource<Object> setSource(Object source) { return this; }
            @Override
            public String url() { return filePath; }
            @Override
            public String username() { return null; }
            @Override
            public String password() { return null; }
        });
    }

    /**
     * 获取 Nitrite 实例。
     *
     * @param name 数据源名称
     * @return Nitrite 实例
     */
    public Nitrite getNitrite(String name) {
        return databases.get(name);
    }

    /**
     * 获取 ObjectRepository。
     *
     * @param name 数据源名称
     * @param entityClass 实体类
     * @param <T> 实体类型
     * @return ObjectRepository
     */
    public <T> ObjectRepository<T> getRepository(String name, Class<T> entityClass) {
        Nitrite nitrite = databases.get(name);
        if (nitrite == null) {
            throw new IllegalArgumentException("Nitrite 数据源未找到: " + name);
        }
        return nitrite.getRepository(entityClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass) {
        List<T> memoryData = getData(entityClass);
        if (!memoryData.isEmpty()) {
            if (where == null || where.trim().isEmpty()) {
                return memoryData;
            }
            MemoryWhereParser parser = new MemoryWhereParser();
            List<Object> paramList = params != null ? Arrays.asList(params) : Collections.emptyList();
            var predicate = parser.parse(where, paramList);
            return memoryData.stream().filter(predicate).toList();
        }
        return Collections.emptyList();
    }

    @Override
    public void close() {
        for (Nitrite nitrite : databases.values()) {
            try { nitrite.close(); } catch (Exception ignored) {}
        }
        databases.clear();
        super.close();
    }
}
