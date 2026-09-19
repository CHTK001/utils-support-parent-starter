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

    /**
     * 数据源未找到错误前缀
    */
    private static final String ERROR_DATASOURCE_NOT_FOUND = "Nitrite 数据源未找到: ";

    /**
     * 日志记录器
     */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(NitriteEngine.class);
    /**
     * 数据库实例映射表
    */
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

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 添加数据源：支持 Nitrite 实例与文件路径两种数据源形态。
     *
     * @param name 数据源名称
     * @param dataSource 数据源封装
     * @param <T> 底层源类型
     * @return 当前引擎实例
     */
    public <T> com.chua.common.support.lang.datasource.engine.Engine addDataSource(
            String name, EngineDataSource<T> dataSource) {
        Object src = dataSource.getSource();
        if (src instanceof Nitrite nitrite) {
            databases.put(name, nitrite);
            super.addDataSource(name, dataSource);
            if (defaultDataSourceName == null) {
                defaultDataSourceName = name;
            }
            return this;
        }
        if (src instanceof String filePath) {
            return addDataSource(name, filePath);
        }
        throw new IllegalArgumentException("Nitrite 数据源类型不支持: "
                + (src == null ? "null" : src.getClass().getName()));
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
     * 获取 对象仓库。
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
    /**
     * 执行新查询
     *
     * @param where where
     * @param params 参数
     * @param entityClass 实体类
     * @param limit 限制
     * @param offset 偏移量
     * @return 执行新查询的结果
     */
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        List<T> memoryData = getData(entityClass);
        if (!memoryData.isEmpty()) {
            if (where == null || where.trim().isEmpty()) {
                return sliceByPage(memoryData, offset, limit);
            }
            MemoryWhereParser parser = new MemoryWhereParser();
            List<Object> paramList = params != null ? Arrays.asList(params) : Collections.emptyList();
            var predicate = parser.parse(where, paramList);
            return sliceByPage(memoryData.stream().filter(predicate).toList(), offset, limit);
        }
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return Collections.emptyList();
        }
        // Nitrite 4.4.x 仓库需注册 EntityConverter，这里用原生集合 + 引擎自研编解码绕过
        NitriteCollection collection = nitrite.getCollection(entityClass.getSimpleName());
        List<T> all = new ArrayList<>();
        for (Document doc : collection.find()) {
            all.add(fromDocument(doc, entityClass));
        }
        if (where == null || where.trim().isEmpty()) {
            return sliceByPage(all, offset, limit);
        }
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> paramList = params != null ? Arrays.asList(params) : Collections.emptyList();
        var predicate = parser.parse(where, paramList);
        return sliceByPage(all.stream().filter(predicate).toList(), offset, limit);
    }

    /**
     * 按 offset/limit 切片，非正值表示不限制。
     *
     * @param data   结果列表
     * @param offset 偏移量
     * @param limit  上限
     * @param <T>    实体类型
     * @return 切片后的列表
     */
    private static <T> List<T> sliceByPage(List<T> data, int offset, int limit) {
        int from = Math.max(offset, 0);
        if (from >= data.size()) {
            return Collections.emptyList();
        }
        int to = limit > 0 ? Math.min(from + limit, data.size()) : data.size();
        return new ArrayList<>(data.subList(from, to));
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 存储：注入内存视图的同时持久化到 Nitrite 仓库（若已连接数据库）。
     *
     * @param name 数据源名称
     * @param data 数据列表
     * @param <T>  实体类型
     * @return 当前引擎实例
     */
    public <T> com.chua.common.support.lang.datasource.engine.Engine store(
            String name, List<T> data) {
        super.store(name, data);
        Nitrite nitrite = currentDatabase();
        if (nitrite == null || data == null || data.isEmpty()) {
            return this;
        }
        Class<T> entityClass = (Class<T>) data.get(0).getClass();
        NitriteCollection collection = nitrite.getCollection(entityClass.getSimpleName());
        for (T entity : data) {
            Document doc = toDocument(entity);
            Object id = doc.get("id");
            if (id != null) {
                collection.update(FluentFilter.where("id").eq(id), doc,
                        org.dizitart.no2.collection.UpdateOptions.updateOptions(true));
            } else {
                collection.insert(doc);
            }
        }
        return this;
    }

    // ==================== FulltextSearch 实现 ====================

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 创建fulltext索引
     *
     * @param entityClass 实体类
     * @param fieldNames 字段名称
     * @return 创建fulltext索引的结果
     */
    public <T> void createFulltextIndex(Class<T> entityClass, String... fieldNames) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return;
        }
        String collectionName = entityClass.getSimpleName();
        try {
            NitriteCollection collection = nitrite.getCollection(collectionName);
            org.dizitart.no2.index.IndexOptions indexOptions = new org.dizitart.no2.index.IndexOptions();
            indexOptions.setIndexType(org.dizitart.no2.index.IndexType.FULL_TEXT);
            collection.createIndex(indexOptions, fieldNames);
        } catch (Exception e) {
            throw new RuntimeException("创建全文索引失败: " + collectionName, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 搜索
     *
     * @param query 查询
     * @param entityClass 实体类
     * @return 搜索的结果
     */
    public <T> List<T> search(String query, Class<T> entityClass) {
        return search(query, entityClass, Integer.MAX_VALUE);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 搜索
     *
     * @param query 查询
     * @param entityClass 实体类
     * @param limit 限制
     * @return 搜索的结果
     */
    public <T> List<T> search(String query, Class<T> entityClass, int limit) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return Collections.emptyList();
        }
        String collectionName = entityClass.getSimpleName();
        try {
            NitriteCollection collection = nitrite.getCollection(collectionName);
            List<org.dizitart.no2.filters.NitriteFilter> textFilters = new ArrayList<>();
            Class<?> cls = entityClass;
            while (cls != null && cls != Object.class) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        textFilters.add(org.dizitart.no2.filters.FluentFilter
                                .where(f.getName()).text(query));
                    }
                }
                cls = cls.getSuperclass();
            }
            if (textFilters.isEmpty()) {
                return Collections.emptyList();
            }
            org.dizitart.no2.filters.Filter textFilter = textFilters.size() == 1
                    ? textFilters.get(0)
                    : org.dizitart.no2.filters.Filter.or(
                            textFilters.toArray(new org.dizitart.no2.filters.Filter[0]));
            List<T> results = new ArrayList<>();
            for (Document doc : collection.find(textFilter)) {
                results.add(fromDocument(doc, entityClass));
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
    /**
     * 掉落fulltext索引
     *
     * @param entityClass 实体类
     * @param fieldNames 字段名称
     * @return 掉落fulltext索引的结果
     */
    public <T> void dropFulltextIndex(Class<T> entityClass, String... fieldNames) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return;
        }
        String collectionName = entityClass.getSimpleName();
        try {
            nitrite.getCollection(collectionName).dropIndex(fieldNames);
        } catch (Exception e) {
            throw new RuntimeException("删除全文索引失败: " + collectionName, e);
        }
    }

    // ==================== DocumentStore 实现 ====================

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 插入
     *
     * @param collection 集合
     * @param document 文档
     * @return 插入的结果
     */
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
    /**
     * 查找byid
     *
     * @param collection 集合
     * @param id 标识
     * @param documentClass 文档类
     * @return findById的结果
     */
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
 // 按业务 标识 字段（字符串比较）匹配，规避数值类型与字符串过滤不匹配
        Document found = findDocByIdField(nitriteCollection, id);
        return found == null ? null : fromDocument(found, documentClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 更新
     *
     * @param collection 集合
     * @param id 标识
     * @param document 文档
     * @return 更新的结果
     */
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
    /**
     * 删除
    */
    public boolean delete(String collection, Object id) {
        Nitrite nitrite = currentDatabase();
        if (nitrite == null) {
            return false;
        }
        NitriteCollection nitriteCollection = nitrite.getCollection(collection);
        if (id instanceof NitriteId nitriteId) {
            org.dizitart.no2.common.WriteResult result =
                    nitriteCollection.remove(org.dizitart.no2.filters.Filter.byId(nitriteId));
            return result.getAffectedCount() > 0;
        }
 // 按业务 标识 字段匹配后删除（使用原始类型值，规避数值/字符串过滤类型不匹配）
        Document found = findDocByIdField(nitriteCollection, id);
        if (found == null) {
            return false;
        }
        org.dizitart.no2.common.WriteResult result = nitriteCollection.remove(
                org.dizitart.no2.filters.FluentFilter.where("id").eq(found.get("id")));
        return result.getAffectedCount() > 0;
    }

    /**
     * 按业务 标识 字段查找文档（字符串比较，兼容数值与字符串类型）。
     *
     * @param collection 集合
     * @param businessId 业务 标识
     * @return 匹配的文档，未找到返回 空
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
    /**
     * 查找全部
     *
     * @param collection 集合
     * @param documentClass 文档类
     * @return find全部的结果
     */
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
     * 将通用对象转换为 Nitrite 文档。
     * <p>如果对象本身就是 Document 则直接返回；否则将其作为 Map 处理。</p>
     *
     * @param source 源对象
     * @return Nitrite 文档
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
        // POJO 实体：反射抽取全字段（含父类），null 字段跳过
        Document doc = Document.createDocument();
        Class<?> cls = source.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object value = com.chua.common.support.reflection.ReflectUtils
                        .getField(source, f.getName());
                if (value != null) {
                    doc.put(f.getName(), value);
                }
            }
            cls = cls.getSuperclass();
        }
        return doc;
    }

    /**
     * 将 Nitrite 文档 转换为目标类型。
     *
     * @param document Nitrite 文档
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
        if (java.util.Map.class.isAssignableFrom(documentClass)) {
            java.util.Map<String, Object> map =
                    (java.util.Map<String, Object>) com.chua.common.support.reflection.ReflectUtils
                            .instantiate(documentClass);
            if (map == null) {
                map = new LinkedHashMap<>();
            }
            for (String field : document.getFields()) {
                map.put(field, document.get(field));
            }
            return (T) map;
        }
        // POJO 目标：反射实例化并按字段类型回填
        T entity = com.chua.common.support.reflection.ReflectUtils.instantiate(documentClass);
        if (entity == null) {
            throw new IllegalStateException("无法实例化文档类型: " + documentClass.getName());
        }
        Class<?> cls = documentClass;
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object value = document.get(f.getName());
                if (value == null) {
                    continue;
                }
                Object converted = f.getType().isInstance(value)
                        ? value
                        : com.chua.common.support.converter.Converter.convertIfNecessary(value, f.getType());
                if (converted != null) {
                    com.chua.common.support.reflection.ReflectUtils.setField(entity, f.getName(), converted);
                }
            }
            cls = cls.getSuperclass();
        }
        return entity;
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        for (Map.Entry<String, Nitrite> entry : databases.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                // 单个库关闭失败不阻断其余，但 Nitrite 未落盘的页可能损坏，必须留痕
                log.warn("Nitrite 数据源关闭失败 name={}: {}", entry.getKey(), e.getMessage(), e);
            }
        }
        databases.clear();
        super.close();
    }
}
