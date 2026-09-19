package com.chua.lucene.support.engine;

import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.lang.ast.parser.SqlExpressionParser;
import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.search.FulltextSearch;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.datasource.support.engine.MemoryWhereParser;
import com.chua.lucene.support.converter.EntityDocumentConverter;
import lombok.extern.slf4j.Slf4j;

import com.chua.common.support.reflection.ReflectUtils;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Apache Lucene 的内存搜索引擎。
 * <p>
 * Luceneengine 继承自 {@link AbstractEngine}，将 SQL 风格的 WHERE 条件通过
 * {@link SqlExpressionParser} 解析为 AST，再转换为 Lucene {@link Query} 执行搜索。
 * </p>
 * <h2>核心架构</h2>
 * <pre>{@code
 * Engine API  →  LambdaQueryWrapper  →  WHERE 子句字符串 + 参数列表
 *                    ↓
 *           SqlExpressionParser.parse()  →  BTreeNode (AST)
 *                    ↓
 *              BTreeNode → Lucene Query（按字段类型构建）
 *                    ↓
 *              IndexSearcher.search(Query, N)
 *                    ↓
 *              TopDocs → Document[] → List<T>
 * }</pre>, N)
 *                    ↓
 *              TopDocs → Document[] → List<T>
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see AbstractEngine
 * @see SqlExpressionParser
 * @see IndexSearcher
 */
 @Spi("lucene")
 @Slf4j
  public class LuceneEngine extends AbstractEngine implements FulltextSearch {

     /**
      * Lucene 索引目录映射表，键为表名，值为 目录 实例。
      */
    private final Map<String, Directory> indexDirectories = new ConcurrentHashMap<>();

    /**
     * 默认索引目录名称。
     */
    private String defaultIndexName;

    /**
     * SQL 表达式解析器，将 SQL WHERE 子句解析为 AST。
     */
    private final SqlExpressionParser sqlParser = new SqlExpressionParser();

    /**
     * Lucene 分析器，用于全文检索。
     */
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    /**
     * 构造 Lucene 引擎，使用内存目录存储索引。
     */
    public LuceneEngine() {
        this(null);
    }

    /**
     * 构造 Lucene 引擎，指定索引根目录。
     * <p>如果指定了目录路径，索引数据持久化到文件系统；
     * 如果为 空，使用 byte缓冲目录（内存）。</p>
     *
     * @param indexPath 索引根目录路径，空 表示内存目录
     */
    public LuceneEngine(Path indexPath) {
        this.indexPath = indexPath;
    }

    /**
     * 索引根目录路径，空 表示使用内存目录
     */
    private final Path indexPath;

    // ==================== Engine 接口实现 ====================

    /**
     * 添加一个数据源到引擎。
     * <p>内存引擎不依赖 JDBC 数据源，此方法仅用于兼容 Engine 接口。</p>
     *
     * @param name       数据源名称
     * @param dataSource 数据源封装实例
     * @param <T>        底层源类型
     * @return this
     */
    @Override
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        dataSources.put(name, (EngineDataSource<Object>) dataSource);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 设置默认数据源名称。
     *
     * @param name 数据源名称
     * @return this
     */
    @Override
    public Engine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    /**
     * 获取指定数据源的 SQL 执行器。
     * <p>Lucene 引擎不使用 JDBC，始终返回 null。</p>
     *
     * @param dataSourceName 数据源名称
     * @return null
     */
    @Override
    public SqlExecutor getExecutor(String dataSourceName) {
        return null;
    }

    /**
     * 获取默认数据源的 SQL 执行器。
     * <p>Lucene 引擎不使用 JDBC，始终返回 null。</p>
     *
     * @return null
     */
    @Override
    public SqlExecutor getExecutor() {
        return null;
    }

    /**
     * 根据名称获取数据源封装对象。
     *
     * @param name 数据源名称
     * @param <T>  底层源类型
     * @return 数据源封装实例，不存在则返回 空
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource(String name) {
        return (EngineDataSource<T>) dataSources.get(name);
    }

    /**
     * 获取默认数据源封装对象。
     *
     * @param <T> 底层源类型
     * @return 默认数据源封装实例
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> EngineDataSource<T> getDataSource() {
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
    }

    /**
     * 获取指定数据源的方言。
     * <p>Lucene 引擎不需要 SQL 方言，始终返回 null。</p>
     *
     * @param dataSourceName 数据源名称
     * @return null
     */
    @Override
    public Dialect getDialect(String dataSourceName) {
        return null;
    }

    /**
     * 关闭引擎，释放所有索引目录资源。
     */
    @Override
    public void close() {
        for (Directory dir : indexDirectories.values()) {
            try {
                dir.close();
            } catch (IOException e) {
                // ignore
            }
        }
        indexDirectories.clear();
        dataSources.clear();
    }

    // ==================== Lucene 索引操作 ====================

    /**
     * 将实体对象列表索引到 Lucene 索引中。
     * <p>
     * 根据实体类名确定表名（驼峰转下划线），在对应的索引目录中写入文档。
     * 如果该表首次被索引，会自动创建 目录。
     * </p>
     *
     * @param entityClass 实体类类型
     * @param entities    实体对象列表
     * @param <T>         实体类型
     * @return 索引的结果
     */
    @SuppressWarnings("unchecked")
    public <T> void index(Class<T> entityClass, List<T> entities) {
        String tableName = getTableName(entityClass);
        Directory directory = getOrCreateDirectory(tableName);
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (T entity : entities) {
                Document doc = EntityDocumentConverter.toDocument(entity);
                writer.addDocument(doc);
            }
            writer.commit();
        } catch (IOException e) {
            throw new RuntimeException("索引失败: " + tableName, e);
        }
    }

    /**
     * 执行全文搜索。
     * <p>
     * 使用 Lucene 默认的 查询parser 解析搜索字符串，在指定表的索引中搜索。
     * </p>
     *
     * @param entityClass 实体类类型
     * @param queryString 搜索查询字符串
     * @param <T>         实体类型
     * @return 匹配的实体列表
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> search(Class<T> entityClass, String queryString) {
        String tableName = getTableName(entityClass);
        Directory directory = indexDirectories.get(tableName);
        if (directory == null) {
            return Collections.emptyList();
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            org.apache.lucene.queryparser.classic.QueryParser parser =
                    new org.apache.lucene.queryparser.classic.QueryParser(LuceneFields.CONTENT, analyzer);
            Query query = parser.parse(queryString);
            TopDocs topDocs = searcher.search(query, 1000);
            return documentToEntities(reader, topDocs.scoreDocs, entityClass);
        } catch (Exception e) {
            throw new RuntimeException("搜索失败: " + queryString, e);
        }
    }

    /**
     * 执行结构化搜索（使用 WHERE 条件转换的 Lucene 查询）。
     *
     * @param tableName  表名
     * @param luceneQuery  Lucene 查询（由 whereclause转换器 生成）
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 匹配的实体列表
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> search(String tableName, Query luceneQuery, Class<T> entityClass) {
        Directory directory = indexDirectories.get(tableName);
        if (directory == null) {
            return Collections.emptyList();
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(luceneQuery, 1000);
            return documentToEntities(reader, topDocs.scoreDocs, entityClass);
        } catch (IOException e) {
            throw new RuntimeException("搜索失败", e);
        }
    }

    // ==================== FulltextSearch 接口实现 ==================

    /**
     * 为指定实体类确保全文索引目录存在。
     * <p>Lucene 的全文索引在调用 {@link #index(Class, List)} 写入文档时自动构建，
     * 此方法仅用于提前初始化索引目录，避免首次搜索时才创建。</p>
     *
     * @param entityClass 实体类类型
     * @param fieldNames 需要建立全文索引的字段名称（Lucene 引擎暂按实体全字段索引，此参数保留以兼容接口）
     * @param <T> 实体类型
     * @return 创建fulltext索引的结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> void createFulltextIndex(Class<T> entityClass, String... fieldNames) {
        getOrCreateDirectory(getTableName(entityClass));
    }

    /**
     * 执行全文检索（符合 {@link FulltextSearch} 接口约定）。
     * <p>委托给 {@link #search(Class, String)} 执行。</p>
     *
     * @param query 搜索关键词
     * @param entityClass 实体类类型
     * @param <T> 实体类型
     * @return 匹配的实体列表
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> search(String query, Class<T> entityClass) {
        return search(entityClass, query);
    }

    /**
     * 执行全文检索（带结果数量限制）。
     * <p>委托给 {@link #search(Class, String)} 执行，并按 limit 截断结果。</p>
     *
     * @param query 搜索关键词
     * @param entityClass 实体类类型
     * @param limit 最大返回条数
     * @param <T> 实体类型
     * @return 匹配的实体列表
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> search(String query, Class<T> entityClass, int limit) {
        List<T> results = search(entityClass, query);
        if (results.size() > limit) {
            return results.subList(0, limit);
        }
        return results;
    }

    /**
     * 删除指定实体类的全文索引。
     * <p>关闭并移除对应的索引目录。</p>
     *
     * @param entityClass 实体类类型
     * @param fieldNames 需要删除索引的字段名称（Lucene 引擎按表级删除，此参数保留以兼容接口）
     * @param <T> 实体类型
     * @return 掉落fulltext索引的结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> void dropFulltextIndex(Class<T> entityClass, String... fieldNames) {
        String tableName = getTableName(entityClass);
        Directory directory = indexDirectories.remove(tableName);
        if (directory != null) {
            try {
                directory.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    // ==================== 分页结构化搜索 ====================

    /**
     * 执行分页结构化搜索。
     *
     * @param tableName  表名
     * @param luceneQuery  Lucene 查询
     * @param pageNum    页码
     * @param pageSize   每页大小
     * @param entityClass 实体类类型
     * @param <T>        实体类型
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    public <T> Page<T> searchPage(String tableName, Query luceneQuery,
                                  int pageNum, int pageSize, Class<T> entityClass) {
        Directory directory = indexDirectories.get(tableName);
        if (directory == null) {
            return new Page<>(pageNum, pageSize, 0, Collections.emptyList());
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(luceneQuery, pageNum * pageSize);
            long total = topDocs.totalHits.value;
            int from = (pageNum - 1) * pageSize;
            int to = Math.min(from + pageSize, (int) total);
            ScoreDoc[] allDocs = topDocs.scoreDocs;
            List<Document> docs = new ArrayList<>();
            for (int i = from; i < to && i < allDocs.length; i++) {
                docs.add(reader.document(allDocs[i].doc));
            }
            List<T> records = new ArrayList<>();
            for (Document doc : docs) {
                T entity = EntityDocumentConverter.toEntity(doc, entityClass);
                if (entity != null) {
                    records.add(entity);
                }
            }
            return new Page<>(pageNum, pageSize, total, records);
        } catch (IOException e) {
            throw new RuntimeException("分页搜索失败", e);
        }
    }

    @SafeVarargs
    /**
     * 分组By
     *
     * @param entityClass 实体类
     * @param groupByCols 群体bycols
     * @return 群体by的结果
     */
    public final <T> GroupByQueryWrapper<T> groupBy(Class<T> entityClass, String... groupByCols) {
        return new GroupByQueryWrapper<>(this, entityClass, groupByCols);
    }

    // ==================== GROUP BY wrapper ====================

    public static final class GroupByQueryWrapper<T> {

        /** 引擎 */
        private final LuceneEngine engine;
        /** Entityclass */
        private final Class<T> entityClass;
        /** 分组bycols */
        private final List<String> groupByCols = new ArrayList<>();
        /** Where */
        private String where;
        /** 参数 */
        private Object[] params;
        /** 排序col */
        private String sortCol;
        /** 排序asc */
        private boolean sortAsc = true;

        GroupByQueryWrapper(LuceneEngine engine, Class<T> entityClass, String... groupByCols) {
            this.engine = engine;
            this.entityClass = entityClass;
            if (groupByCols != null) {
                for (String c : groupByCols) {
                    if (c != null && !c.isEmpty()) {
                        this.groupByCols.add(c);
                    }
                }
            }
        }

        /**
        * Where
        *
        * @param where where
        * @param params 参数
        * @return where的结果
        */
        public GroupByQueryWrapper<T> where(String where, Object... params) {
            this.where = where;
            this.params = params;
            return this;
        }

        /**
         * 订单by
         *
         * @param col col
         * @param asc asc
         * @return 订单by的结果
         */
        public GroupByQueryWrapper<T> orderBy(String col, boolean asc) {
            this.sortCol = col;
            this.sortAsc = asc;
            return this;
        }

        /**
         * 列表
         *
         * @return 列表的结果
         */
        public List<Map<String, Object>> list() {
            List<T> entities = engine.executeNewQuery(where, params, entityClass, 0, 0);
            return groupEntities(entities, groupByCols);
        }

        /**
         * Page
         *
         * @param pn pn
         * @param ps ps
         * @return page的结果
         */
        public Page<Map<String, Object>> page(int pn, int ps) {
            List<T> all = engine.executeNewQuery(where, params, entityClass, 0, 0);
            List<Map<String, Object>> grouped = groupEntities(all, groupByCols);
            int from = (pn - 1) * ps;
            int to = Math.min(from + ps, grouped.size());
            if (from >= grouped.size()) {
                return new Page<>(pn, ps, grouped.size(), Collections.emptyList());
            }
            return new Page<>(pn, ps, grouped.size(), grouped.subList(from, to));
        }

        /**
         * 分组Entities
         *
         * @param entities 实体
         * @param groupByCols 群体bycols
         * @return 群体实体的结果
         */
        private static <T> List<Map<String, Object>> groupEntities(List<T> entities, List<String> groupByCols) {
            if (entities.isEmpty() || groupByCols.isEmpty()) {
                return Collections.emptyList();
            }

            Comparator<List<Object>> listComparator = (a, b) -> {
                int len = Math.min(a.size(), b.size());
                for (int i = 0; i < len; i++) {
                    Comparable ca = toComparable(a.get(i));
                    Comparable cb = toComparable(b.get(i));
                    int cmp = compareNullable(ca, cb);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
                return Integer.compare(a.size(), b.size());
            };

            if (groupByCols.size() == 1) {
                String col = groupByCols.getFirst();
                Map<Object, Long> counts = new TreeMap<>();
                for (T entity : entities) {
                    Object val = getFieldValue(entity, col);
                    counts.merge(val, 1L, Long::sum);
                }
                List<Map<String, Object>> result = new ArrayList<>();
                for (Map.Entry<Object, Long> entry : counts.entrySet()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(col, entry.getKey());
                    row.put("count", entry.getValue());
                    result.add(row);
                }
                return result;
            }

            Map<List<Object>, Long> counts = new TreeMap<>(listComparator);
            for (T entity : entities) {
                List<Object> keys = new ArrayList<>();
                for (String col : groupByCols) {
                    keys.add(getFieldValue(entity, col));
                }
                counts.merge(keys, 1L, Long::sum);
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map.Entry<List<Object>, Long> entry : counts.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                List<Object> keys = entry.getKey();
                for (int i = 0; i < groupByCols.size(); i++) {
                    row.put(groupByCols.get(i), keys.get(i));
                }
                row.put("count", entry.getValue());
                result.add(row);
            }
            return result;
        }

        /**
         * 获取字段值
         *
         * @param obj obj
         * @param fieldName 字段名称
         * @return 获取字段值的结果
         */
        private static Object getFieldValue(Object obj, String fieldName) {
            if (obj == null || fieldName == null) {
                return null;
            }
            String getter = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            for (var method : obj.getClass().getMethods()) {
                if (method.getName().equals(getter) && method.getParameterCount() == 0) {
                    try {
                        return ReflectUtils.invoke(obj, method.getName(), method.getReturnType());
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
            String isGetter = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            for (var method : obj.getClass().getMethods()) {
                if (method.getName().equals(isGetter) && method.getParameterCount() == 0
                        && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                    try {
                        return ReflectUtils.invoke(obj, method.getName(), method.getReturnType());
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
            return null;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        /**
         * 转为comparable
         *
         * @param value 值
         * @return 转为comparable的结果
         */
        private static Comparable toComparable(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Comparable) {
                return (Comparable) value;
            }
            return value.toString();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        /**
         * 比较Nullable
         *
         * @param a a
         * @param b b
         * @return compare空的结果
         */
        private static int compareNullable(Comparable a, Comparable b) {
            if (a == null && b == null) {
                return 0;
            }
            if (a == null) {
                return -1;
            }
            if (b == null) {
                return 1;
            }
            return a.compareTo(b);
        }
    }

    // ==================== executeNewQuery 实现 ====================

    /**
     * 执行基于 WHERE 条件的 Lucene 搜索。
     * <p>
     * 核心流程：
     * <ol>
     *   <li>替换 {@code ?} 占位符为实际参数值</li>
     *   <li>通过 {@link SqlExpressionParser} 解析 SQL WHERE 为 AST（{@link BTreeNode}）</li>
     *   <li>遍历 AST，按字段 Java 类型构建对应的 Lucene {@link Query}</li>
     *   <li>使用 {@link IndexSearcher} 执行搜索</li>
     * </ol>
     * </p>
     *
     * @param where       WHERE 子句（不含 "WHERE" 关键字）
     * @param params      参数值数组
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 查询结果列表
     */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        List<T> data = getData(entityClass);
        if (!data.isEmpty()) {
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

        String tableName = getTableName(entityClass);
        Directory directory = indexDirectories.get(tableName);
        if (directory == null) {
            return Collections.emptyList();
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query luceneQuery;
            if (where == null || where.trim().isEmpty()) {
                luceneQuery = new MatchAllDocsQuery();
            } else {
                String resolved = resolvePlaceholders(where, params);
                BTreeNode tree = sqlParser.parse(resolved);
                luceneQuery = buildQuery(tree, entityClass);
            }
            TopDocs topDocs = searcher.search(luceneQuery, 1000);
            return documentToEntities(reader, topDocs.scoreDocs, entityClass);
        } catch (IOException e) {
            throw new RuntimeException("查询失败: " + where, e);
        }
    }

    /**
     * 替换 SQL 中的 {@code ?} 占位符为实际参数值。
     * <p>字符串值自动加单引号，数值原样输出，null 转为 NULL。</p>
     * @param where where
     * @param params 参数
     * @return resolvePlaceholders的结果
     */
    protected String resolvePlaceholders(String where, Object[] params) {
        if (params == null || params.length == 0) {
            return where;
        }
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (int i = 0; i < where.length(); i++) {
            char c = where.charAt(i);
            if (c == '?' && idx < params.length) {
                Object val = params[idx++];
                if (val == null) {
                    sb.append("NULL");
                } else if (val instanceof Number || val instanceof Boolean) {
                    sb.append(val);
                } else {
                    sb.append('\'').append(val.toString().replace("'", "''")).append('\'');
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 将 AST 节点递归转换为 Lucene 查询。
     * @param node 节点
     * @param entityClass 实体类
     * @return 构建查询的结果
     */
    protected Query buildQuery(BTreeNode node, Class<?> entityClass) {
        return switch (node.getType()) {
            case LOGIC -> {
                BooleanQuery.Builder bb = new BooleanQuery.Builder();
                BooleanClause.Occur occur = "AND".equalsIgnoreCase(node.getOperator())
                        ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD;
                bb.add(buildQuery(node.getLeft(), entityClass), occur);
                bb.add(buildQuery(node.getRight(), entityClass), occur);
                yield bb.build();
            }
            case NOT -> {
                BooleanQuery.Builder bb = new BooleanQuery.Builder();
                bb.add(new BooleanClause(buildQuery(node.getRight(), entityClass), BooleanClause.Occur.MUST_NOT));
                yield bb.build();
            }
            case COMPARE -> buildComparison(node, entityClass);
            default -> new MatchAllDocsQuery();
        };
    }

    /**
     * 构建比较节点的 Lucene 查询。
     * @param node 节点
     * @param entityClass 实体类
     * @return 构建comparison的结果
     */
    @SuppressWarnings("unchecked")
    private Query buildComparison(BTreeNode node, Class<?> entityClass) {
        String field = node.getLeft().asString();
        String op = node.getOperator();

        if ("IS NULL".equalsIgnoreCase(op) || "IS NOT NULL".equalsIgnoreCase(op)) {
            return "IS NOT NULL".equalsIgnoreCase(op) ? new MatchAllDocsQuery() : new MatchNoDocsQuery();
        }

        if ("IN".equalsIgnoreCase(op)) {
            Object raw = node.getRight().getValue();
            String listStr = raw instanceof String s ? s : String.valueOf(raw);
            List<Object> values = parseInList(listStr);
            BooleanQuery.Builder bb = new BooleanQuery.Builder();
            for (Object v : values) {
                bb.add(exactQuery(field, v, entityClass), BooleanClause.Occur.SHOULD);
            }
            return bb.build();
        }

        if ("BETWEEN".equalsIgnoreCase(op)) {
            BTreeNode right = node.getRight();
            Object low = right.getLeft().getValue();
            Object high = right.getRight().getValue();
            return rangeQuery(field, low, high, true, true, entityClass);
        }

        Object value = node.getRight().getValue();

        return switch (op) {
            case "=" -> exactQuery(field, value, entityClass);
            case "!=" -> {
                BooleanQuery.Builder bb = new BooleanQuery.Builder();
                bb.add(new BooleanClause(exactQuery(field, value, entityClass), BooleanClause.Occur.MUST_NOT));
                yield bb.build();
            }
            case ">" -> rangeQuery(field, value, null, false, false, entityClass);
            case ">=" -> rangeQuery(field, value, null, true, false, entityClass);
            case "<" -> rangeQuery(field, null, value, false, false, entityClass);
            case "<=" -> rangeQuery(field, null, value, false, true, entityClass);
            case "LIKE" -> {
                String pattern = String.valueOf(value).replace("%", "*").replace("_", "?");
                yield new WildcardQuery(new Term(field, pattern));
            }
            default -> new MatchAllDocsQuery();
        };
    }

    /**
     * 字段精确匹配查询（=）。
     * <p>根据字段的 Java 类型选择正确的 Lucene Query 子类。</p>
     * @param field 字段
     * @param value 值
     * @param entityClass 实体类
     * @return exact查询的结果
     */
    private Query exactQuery(String field, Object value, Class<?> entityClass) {
        Class<?> type = resolveFieldType(field, entityClass);
        if (type == null) {
            return null;
        }
        if (type == Integer.class || type == int.class) {
            return IntPoint.newExactQuery(field, ((Number) value).intValue());
        }
        if (type == Long.class || type == long.class) {
            return LongPoint.newExactQuery(field, ((Number) value).longValue());
        }
        if (type == Float.class || type == float.class) {
            return FloatPoint.newExactQuery(field, ((Number) value).floatValue());
        }
        if (type == Double.class || type == double.class) {
            return DoublePoint.newExactQuery(field, ((Number) value).doubleValue());
        }
        if (type == Short.class || type == short.class || type == Byte.class || type == byte.class) {
            return IntPoint.newExactQuery(field, ((Number) value).intValue());
        }
        if (type == Date.class || type == LocalDateTime.class || type == LocalDate.class) {
            return LongPoint.newExactQuery(field, toEpochMillis(value, type));
        }
        return new TermQuery(new Term(field, String.valueOf(value)));
    }

    /**
     * 范围查询（>, >=, <, <=, BETWEEN）。
     */
    private Query rangeQuery(String field, Object low, Object high,
                             boolean lowIncl, boolean highIncl, Class<?> entityClass) {
        Class<?> type = resolveFieldType(field, entityClass);
        if (type == null) {
            String lowStr = low != null ? String.valueOf(low) : "*";
            String highStr = high != null ? String.valueOf(high) : "*";
            return new TermRangeQuery(field, new BytesRef(lowStr), new BytesRef(highStr), lowIncl, highIncl);
        }

        if (type == Integer.class || type == int.class) {
            int l = low != null ? ((Number) low).intValue() : Integer.MIN_VALUE;
            int h = high != null ? ((Number) high).intValue() : Integer.MAX_VALUE;
            if (!lowIncl && low != null) {
                l = Math.addExact(l, 1);
            }
            if (!highIncl && high != null) {
                h = Math.addExact(h, -1);
            }
            return IntPoint.newRangeQuery(field, l, h);
        }
        if (type == Long.class || type == long.class) {
            long l = low != null ? ((Number) low).longValue() : Long.MIN_VALUE;
            long h = high != null ? ((Number) high).longValue() : Long.MAX_VALUE;
            if (!lowIncl && low != null) {
                l = Math.addExact(l, 1);
            }
            if (!highIncl && high != null) {
                h = Math.addExact(h, -1);
            }
            return LongPoint.newRangeQuery(field, l, h);
        }
        if (type == Float.class || type == float.class) {
            float l = low != null ? ((Number) low).floatValue() : Float.NEGATIVE_INFINITY;
            float h = high != null ? ((Number) high).floatValue() : Float.POSITIVE_INFINITY;
            if (!lowIncl && low != null) {
                l = Math.nextUp(l);
            }
            if (!highIncl && high != null) {
                h = Math.nextDown(h);
            }
            return FloatPoint.newRangeQuery(field, l, h);
        }
        if (type == Double.class || type == double.class) {
            double l = low != null ? ((Number) low).doubleValue() : Double.NEGATIVE_INFINITY;
            double h = high != null ? ((Number) high).doubleValue() : Double.POSITIVE_INFINITY;
            if (!lowIncl && low != null) {
                l = Math.nextUp(l);
            }
            if (!highIncl && high != null) {
                h = Math.nextDown(h);
            }
            return DoublePoint.newRangeQuery(field, l, h);
        }
        if (type == Short.class || type == short.class || type == Byte.class || type == byte.class) {
            int l = low != null ? ((Number) low).intValue() : Integer.MIN_VALUE;
            int h = high != null ? ((Number) high).intValue() : Integer.MAX_VALUE;
            if (!lowIncl && low != null) {
                l = Math.addExact(l, 1);
            }
            if (!highIncl && high != null) {
                h = Math.addExact(h, -1);
            }
            return IntPoint.newRangeQuery(field, l, h);
        }
        if (type == Date.class || type == LocalDateTime.class || type == LocalDate.class) {
            long l = low != null ? toEpochMillis(low, type) : Long.MIN_VALUE;
            long h = high != null ? toEpochMillis(high, type) : Long.MAX_VALUE;
            if (!lowIncl && low != null) {
                l = Math.addExact(l, 1);
            }
            if (!highIncl && high != null) {
                h = Math.addExact(h, -1);
            }
            return LongPoint.newRangeQuery(field, l, h);
        }
        String lowStr = low != null ? String.valueOf(low) : "*";
        String highStr = high != null ? String.valueOf(high) : "*";
        return new TermRangeQuery(field, new BytesRef(lowStr), new BytesRef(highStr), lowIncl, highIncl);
    }

    /**
     * 反射获取实体字段的 Java 类型。
     * @param fieldName 字段名称
     * @param entityClass 实体类
     * @return resolve字段类型的结果
     */
    private Class<?> resolveFieldType(String fieldName, Class<?> entityClass) {
        java.lang.reflect.Field f = ReflectUtils.findField(entityClass, fieldName);
        return f != null ? f.getType() : null;
    }

    /**
     * 解析 入 列表字符串 {@code ('a','b')} 或 {@code (1,2,3)} 为值列表。
     * @param list 列表
     * @return 解析入列表的结果
     */
    private List<Object> parseInList(String list) {
        List<Object> result = new ArrayList<>();
        String trimmed = list.trim();
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            int i = 0;
            while (i < trimmed.length()) {
                if (trimmed.charAt(i) == '\'' || trimmed.charAt(i) == '"') {
                    char quote = trimmed.charAt(i);
                    int end = trimmed.indexOf(quote, i + 1);
                    if (end < 0) {
                        break;
                    }
                    result.add(trimmed.substring(i + 1, end));
                    i = end + 1;
                } else if (trimmed.charAt(i) == ',') {
                    i++;
                } else if (!Character.isWhitespace(trimmed.charAt(i))) {
                    int end = i;
                    while (end < trimmed.length() && trimmed.charAt(end) != ',' && !Character.isWhitespace(trimmed.charAt(end))) {
                        end++;
                    }
                    String token = trimmed.substring(i, end).trim();
                    if (!token.isEmpty()) {
                        if (token.contains(".")) {
                            continue;
                        }
                        else {
                            result.add(Integer.parseInt(token));
                        }
                    }
                    i = end;
                } else {
                    i++;
                }
            }
        }
        return result;
    }

    /**
     * 将日期/时间值转换为毫秒时间戳。
     * @param value 值
     * @param type 类型
     * @return 转为轮次millis的结果
     */
    private static long toEpochMillis(Object value, Class<?> type) {
        if (type == Date.class) {
            return ((Date) value).getTime();
        }
        if (type == LocalDateTime.class) {
            return ((LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        }
        if (type == LocalDate.class) {
            return ((LocalDate) value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        }
        return ((Number) value).longValue();
    }

    /**
     * 获取指定实体类对应的数据列表。
     * <p>
     * 优先从内存 数据存储 返回通过 存储() 注入的数据；
     * 若 数据存储 为空，则返回空列表（数据通过 索引() 进入 Lucene 索引）。
     * </p>
     *
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 数据列表
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getData(Class<T> entityClass) {
        return super.getData(entityClass);
    }

    /**
     * 将实体类名称转换为表名。
     * <p>
     * 转换规则：驼峰命名转下划线命名。
     * 例如：{@code UserInfo} → {@code user_info}，{@code User} → {@code user}。
     * </p>
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 表名
     */
    @Override
    public <T> String getTableName(Class<T> entityClass) {
        var simpleName = entityClass.getSimpleName();
        var sb = new StringBuilder();
        for (char c : simpleName.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ==================== 内部方法 ====================

    /**
     * 获取或创建指定表名的索引目录。
     *
     * @param tableName 表名
     * @return Directory 实例
     */
    Directory getOrCreateDirectory(String tableName) {
        return indexDirectories.computeIfAbsent(tableName, name -> {
            try {
                return indexPath != null
                        ? FSDirectory.open(indexPath.resolve(name))
                        : new org.apache.lucene.store.ByteBuffersDirectory();
            } catch (IOException e) {
                throw new RuntimeException("创建索引目录失败: " + name, e);
            }
        });
    }

    @Override
    /** 执行更新 */
    public <T> int executeUpdate(UpdateSql<T> sql) {
        if (sql == null || !sql.hasSet() || !sql.hasWhere()) {
            return 0;
        }

        String setClause = sql.setClause();
        List<Object> params = sql.params();
        String[] setParts = setClause.split(", ");
        Map<String, Object> setValues = new LinkedHashMap<>();
        for (int i = 0; i < setParts.length; i++) {
            int eqIdx = setParts[i].indexOf(" = ");
            if (eqIdx > 0) {
                setValues.put(setParts[i].substring(0, eqIdx), params.get(i));
            }
        }

        int setCount = setParts.length;
        Object[] whereParams = params.size() > setCount
                ? params.subList(setCount, params.size()).toArray(new Object[0])
                : new Object[0];

        String whereClause = sql.whereClause();
        Class<T> entityClass = sql.entityClass();

        try {
            List<Document> matchedDocs = searchDocuments(whereClause, whereParams, entityClass);
            if (matchedDocs.isEmpty()) {
                return 0;
            }

            String tableName = getTableName(entityClass);
            Directory directory = getOrCreateDirectory(tableName);
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
                int updated = 0;
                for (Document oldDoc : matchedDocs) {
                    String id = oldDoc.get(LuceneFields.ID);
                    if (id == null) {
                        continue;
                    }

                    Document newDoc = new Document();
                    for (IndexableField field : oldDoc.getFields()) {
                        String name = field.name();
                        if (LuceneFields.ID.equals(name) || LuceneFields.VERSION.equals(name)) {
                            continue;
                        }
                        newDoc.add(field);
                    }

                    for (Map.Entry<String, Object> entry : setValues.entrySet()) {
                        String fieldName = entry.getKey();
                        Object newValue = entry.getValue();
                        newDoc.removeFields(fieldName);
                        if (newValue != null) {
                            addFieldToDoc(newDoc, fieldName, newValue);
                        }
                    }

                    writer.updateDocument(new Term(LuceneFields.ID, id), newDoc);
                    updated++;
                }
                writer.commit();
            }

            super.executeUpdate(sql);
            return matchedDocs.size();
        } catch (Exception e) {
            log.warn("[lucene-engine] 更新失败: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Override
    /** 执行删除 */
    public <T> int executeDelete(DeleteSql<T> sql) {
        if (sql == null || !sql.hasWhere()) {
            return 0;
        }

        String whereClause = sql.whereClause();
        List<Object> params = sql.params();
        Object[] whereParams = params.toArray(new Object[0]);
        Class<T> entityClass = sql.entityClass();

        try {
            List<Document> matchedDocs = searchDocuments(whereClause, whereParams, entityClass);
            int count = matchedDocs.size();
            if (count == 0) {
                return 0;
            }

            String tableName = getTableName(entityClass);
            Directory directory = getOrCreateDirectory(tableName);
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
                writer.deleteDocuments(buildQueryFromWhere(whereClause, whereParams, entityClass));
                writer.commit();
            }

            super.executeDelete(sql);
            return count;
        } catch (Exception e) {
            log.warn("[lucene-engine] 删除失败: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 构建查询从where
     *
     * @param where where
     * @param params 参数
     * @param entityClass 实体类
     * @return 构建查询从where的结果
     */
    protected <T> Query buildQueryFromWhere(String where, Object[] params, Class<T> entityClass) {
        if (where == null || where.trim().isEmpty()) {
            return new MatchAllDocsQuery();
        }
        String resolved = resolvePlaceholders(where, params);
        BTreeNode tree = sqlParser.parse(resolved);
        return buildQuery(tree, entityClass);
    }

    /**
     * 搜索文档
     *
     * @param where where
     * @param params 参数
     * @param entityClass 实体类
     * @return 搜索文档的结果
     */
    private <T> List<Document> searchDocuments(String where, Object[] params, Class<T> entityClass) throws IOException {
        Query query = buildQueryFromWhere(where, params, entityClass);
        Directory directory = getOrCreateDirectory(getTableName(entityClass));
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(query, 10000);
            List<Document> docs = new ArrayList<>();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                docs.add(reader.document(sd.doc));
            }
            return docs;
        }
    }

    /**
     * 添加字段转为doc
     *
     * @param doc doc
     * @param fieldName 字段名称
     * @param value 值
     */
    static void addFieldToDoc(Document doc, String fieldName, Object value) {
        if (value instanceof String str) {
            doc.add(new StringField(fieldName, str, org.apache.lucene.document.Field.Store.YES));
            doc.add(new TextField(fieldName + "_text", str, org.apache.lucene.document.Field.Store.NO));
        } else if (value instanceof Long l) {
            doc.add(new LongPoint(fieldName, l));
            doc.add(new StoredField(fieldName, l));
        } else if (value instanceof Integer i) {
            doc.add(new IntPoint(fieldName, i));
            doc.add(new StoredField(fieldName, i));
        } else if (value instanceof Short s) {
            doc.add(new IntPoint(fieldName, s));
            doc.add(new StoredField(fieldName, s));
        } else if (value instanceof Byte b) {
            doc.add(new IntPoint(fieldName, b));
            doc.add(new StoredField(fieldName, b));
        } else if (value instanceof Float f) {
            doc.add(new FloatPoint(fieldName, f));
            doc.add(new StoredField(fieldName, f));
        } else if (value instanceof Double d) {
            doc.add(new DoublePoint(fieldName, d));
            doc.add(new StoredField(fieldName, d));
        } else if (value instanceof BigDecimal bd) {
            doc.add(new DoublePoint(fieldName, bd.doubleValue()));
            doc.add(new StoredField(fieldName, bd.toString()));
        } else if (value instanceof Boolean b) {
            doc.add(new StoredField(fieldName, b ? "true" : "false"));
        } else if (value instanceof Date date) {
            doc.add(new LongPoint(fieldName, date.getTime()));
            doc.add(new StoredField(fieldName, String.valueOf(date.getTime())));
        } else if (value instanceof LocalDateTime ldt) {
            doc.add(new LongPoint(fieldName, ldt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()));
            doc.add(new StoredField(fieldName, ldt.toString()));
        } else if (value instanceof LocalDate ld) {
            doc.add(new LongPoint(fieldName, ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()));
            doc.add(new StoredField(fieldName, ld.toString()));
        } else if (value instanceof LocalTime lt) {
            doc.add(new StoredField(fieldName, lt.toString()));
        } else {
            doc.add(new StringField(fieldName, String.valueOf(value), org.apache.lucene.document.Field.Store.YES));
        }
    }

    /**
     * 将 Lucene scoredoc 数组转换为实体对象列表。
     * <p>
     * 通过 文档.标识 查找原始实体对象（从 数据存储 缓存）。
     * </p>
     *
     * @param reader      Lucene 目录读取
     * @param scoreDocs   搜索结果
     * @param entityClass 实体类类型
     * @param <T>         实体类型
     * @return 实体对象列表
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> documentToEntities(DirectoryReader reader, ScoreDoc[] scoreDocs, Class<T> entityClass) {
        List<T> results = new ArrayList<>(scoreDocs.length);
        for (ScoreDoc sd : scoreDocs) {
            try {
                Document doc = reader.document(sd.doc);
                T entity = EntityDocumentConverter.toEntity(doc, entityClass);
                if (entity != null) {
                    results.add(entity);
                }
            } catch (IOException e) {
 // 跳过
            }
        }
        return results;
    }
}
