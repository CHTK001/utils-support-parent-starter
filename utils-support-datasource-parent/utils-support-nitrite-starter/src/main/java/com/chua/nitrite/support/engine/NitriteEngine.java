package com.chua.nitrite.support.engine;

import com.chua.common.support.lang.datasource.search.DocumentStore;
import com.chua.common.support.lang.datasource.search.FulltextSearch;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.engine.MemoryWhereParser;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.collection.NitriteId;
import org.dizitart.no2.collection.DocumentCursor;
import org.dizitart.no2.filters.FluentFilter;
import org.dizitart.no2.mvstore.MVStoreModule;
import org.dizitart.no2.NitriteBuilder;
import org.dizitart.no2.repository.ObjectRepository;
import org.dizitart.no2.index.IndexOptions;
import org.dizitart.no2.common.Fields;
import org.dizitart.no2.common.FieldValues;
import org.dizitart.no2.index.fulltext.TextTokenizer;
import org.dizitart.no2.index.fulltext.Languages;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nitrite 文档数据库引擎，基于内存过滤和文件存储。
 *
 * @author CH
 * @since 4.0.0.42
 */
 @Spi("nitrite")
 public class NitriteEngine extends AbstractEngine implements FulltextSearch, DocumentStore {

    /** 数据源未找到错误前缀 */
    private static final String ERROR_DATASOURCE_NOT_FOUND = "Nitrite 数据源未找到: ";
    /** 数据库实例映射表 */
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
        super.addDataSource(name, new NitriteEngineDataSource(name, nitrite, filePath));
        return this;
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
            throw new IllegalArgumentException(ERROR_DATASOURCE_NOT_FOUND + name);
        }
        return nitrite.getRepository(entityClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 执行New查询 */
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

    // ==================== FulltextSearch 实现 ====================

    @Override
    @SuppressWarnings("unchecked")
    /** 创建FulltextIndex */
    public <T> void createFulltextIndex(Class<T> entityClass, String... fieldNames) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return;
        }
        String repositoryName = entityClass.getSimpleName();
        try {
            ObjectRepository<T> repository = nitrite.getRepository(entityClass, repositoryName);
            org.dizitart.no2.index.IndexOptions indexOptions = new org.dizitart.no2.index.IndexOptions();
            indexOptions.setIndexType(org.dizitart.no2.index.IndexType.FULL_TEXT);
            repository.createIndex(indexOptions, fieldNames);
        } catch (Exception e) {
            throw new RuntimeException("创建全文索引失败: " + repositoryName, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 搜索 */
    public <T> List<T> search(String query, Class<T> entityClass) {
        return search(query, entityClass, Integer.MAX_VALUE);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 搜索 */
    public <T> List<T> search(String query, Class<T> entityClass, int limit) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return Collections.emptyList();
        }
        String repositoryName = entityClass.getSimpleName();
        try {
            ObjectRepository<T> repository = nitrite.getRepository(entityClass, repositoryName);
            org.dizitart.no2.filters.NitriteFilter textFilter =
                    org.dizitart.no2.filters.FluentFilter.where(repositoryName).text(query);
            List<T> results = new ArrayList<>();
            for (T item : repository.find(textFilter)) {
                results.add(item);
                if (results.size() >= limit) {
                    break;
                }
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException("全文检索失败: " + query, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    /** DropFulltextIndex */
    public <T> void dropFulltextIndex(Class<T> entityClass, String... fieldNames) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return;
        }
        String repositoryName = entityClass.getSimpleName();
        try {
            ObjectRepository<T> repository = nitrite.getRepository(entityClass, repositoryName);
            repository.dropIndex(fieldNames);
        } catch (Exception e) {
            throw new RuntimeException("删除全文索引失败: " + repositoryName, e);
        }
    }

    // ==================== DocumentStore 实现 ====================

    @Override
    @SuppressWarnings("unchecked")
    /** 插入 */
    public <T> T insert(String collection, T document) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            throw new IllegalStateException("Nitrite 数据源未连接");
        }
        NitriteCollection nitriteCollection = nitrite.getCollection(collection);
        Document doc = toDocument(document);
        nitriteCollection.insert(doc);
        return (T) fromDocument(doc, document.getClass());
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 查找ById */
    public <T> T findById(String collection, Object id, Class<T> documentClass) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return null;
        }
        NitriteCollection nitriteCollection = nitrite.getCollection(collection);
        if (id instanceof NitriteId nitriteId) {
            Document doc = nitriteCollection.getById(nitriteId);
            return doc == null ? null : fromDocument(doc, documentClass);
        }
        // 按业务 id 字段（字符串比较）匹配，规避数值类型与字符串过滤不匹配
        Document found = findDocByIdField(nitriteCollection, id);
        return found == null ? null : fromDocument(found, documentClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 更新 */
    public <T> T update(String collection, Object id, T document) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return null;
        }
        NitriteCollection nitriteCollection = nitrite.getCollection(collection);
        Document existing = null;
        if (id instanceof NitriteId nitriteId) {
            existing = nitriteCollection.getById(nitriteId);
        }
        if (existing == null) {
            org.dizitart.no2.filters.Filter filter =
                    org.dizitart.no2.filters.FluentFilter.where("id").eq(String.valueOf(id));
            for (Document doc : nitriteCollection.find(filter)) {
                existing = doc;
                break;
            }
        }
        if (existing == null) {
            return null;
        }
        Document newDoc = toDocument(document);
        org.dizitart.no2.filters.Filter updateFilter =
                org.dizitart.no2.filters.FluentFilter.where("id").eq(String.valueOf(id));
        nitriteCollection.update(updateFilter, newDoc);
        return (T) fromDocument(newDoc, document.getClass());
    }

    @Override
    /** 删除 */
    public boolean delete(String collection, Object id) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return false;
        }
        NitriteCollection nitriteCollection = nitrite.getCollection(collection);
        if (id instanceof NitriteId nitriteId) {
            Document doc = nitriteCollection.getById(nitriteId);
            if (doc == null) {
                return false;
            }
            nitriteCollection.remove(org.dizitart.no2.filters.FluentFilter.where("_id").eq(nitriteId));
            return true;
        }
        // 按业务 id 字段匹配后删除
        Document found = findDocByIdField(nitriteCollection, id);
        if (found == null) {
            return false;
        }
        if (found.hasId()) {
            nitriteCollection.remove(org.dizitart.no2.filters.FluentFilter.where("_id").eq(found.getId()));
        } else {
            nitriteCollection.remove(org.dizitart.no2.filters.FluentFilter.where("id").eq(found.get("id")));
        }
        return true;
    }

    /**
     * 按业务 id 字段查找文档（字符串比较，兼容数值与字符串类型）。
     *
     * @param collection 集合
     * @param businessId 业务 id
     * @return 匹配的文档，未找到返回 null
     */
    private static Document findDocByIdField(NitriteCollection collection, Object businessId) {
        for (Document doc : collection.find()) {
            Object value = doc.get("id");
            if (value != null && value.toString().equals(businessId.toString())) {
                return doc;
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    /** 查找All */
    public <T> List<T> findAll(String collection, Class<T> documentClass) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return Collections.emptyList();
        }
        NitriteCollection nitriteCollection = nitrite.getCollection(collection);
        List<T> results = new ArrayList<>();
        for (Document doc : nitriteCollection.find()) {
            results.add(fromDocument(doc, documentClass));
        }
        return results;
    }

    // ==================== Nitrite 辅助方法 ====================

    /**
     * 获取当前默认数据源对应的 Nitrite 实例。
     *
     * @return Nitrite 实例
     */
    private Nitrite currentDatabase() {
        if (defaultDataSourceName == null) {
            if (databases.isEmpty()) {
                return null;
            }
            return databases.values().iterator().next();
        }
        return databases.get(defaultDataSourceName);
    }

    /**
     * 将通用对象转换为 Nitrite Document。
     * <p>如果对象本身就是 Document 则直接返回；否则将其作为 Map 处理。</p>
     *
     * @param source 源对象
     * @return Nitrite Document
     */
    private static Document toDocument(Object source) {
        if (source instanceof Document doc) {
            return doc;
        }
        if (source instanceof java.util.Map<?, ?> map) {
            Document doc = Document.createDocument();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                doc.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return doc;
        }
        throw new IllegalArgumentException("不支持的文档类型: " + source.getClass().getName());
    }

    /**
     * 将 Nitrite Document 转换为目标类型。
     *
     * @param document Nitrite Document
     * @param documentClass 目标类型
     * @param <T> 目标泛型
     * @return 目标类型实例
     */
    @SuppressWarnings("unchecked")
    private static <T> T fromDocument(Document document, Class<T> documentClass) {
        if (documentClass == null) {
            return (T) document;
        }
        if (documentClass.isInstance(document)) {
            return documentClass.cast(document);
        }
        if (documentClass == java.util.Map.class) {
            java.util.Map<String, Object> map = new LinkedHashMap<>();
            for (String field : document.getFields()) {
                map.put(field, document.get(field));
            }
            return documentClass.cast(map);
        }
        return documentClass.cast(document);
    }

    @Override
    /** 关闭 */
    public void close() {
        for (Nitrite nitrite : databases.values()) {
            try {
                nitrite.close();
            } catch (Exception ignored) {
            }
        }
        databases.clear();
        super.close();
    }
}
