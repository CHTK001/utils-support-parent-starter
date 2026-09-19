package com.chua.solr.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.solr.support.meta.SolrMetaData;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.solr.support.meta.SolrSearchEngine;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.json.JsonFacetMap;
import org.apache.solr.client.solrj.request.json.JsonQueryRequest;
import org.apache.solr.client.solrj.request.json.TermsFacetMap;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.json.BucketBasedJsonFacet;
import org.apache.solr.client.solrj.response.json.BucketJsonFacet;
import org.apache.solr.client.solrj.response.json.NestableJsonFacet;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;

import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.reflection.ReflectUtils;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("solr")
public class SolrEngine extends AbstractEngine {

    /**
     * update() 游标深分页每批拉取行数
     */
    private static final int UPDATE_BATCH = 1000;

    /**
     * 客户端
    */
    private SolrClient client;
    /**
     * 默认数据来源名称
    */
    private String defaultDataSourceName;

    @Override
    /**
     * 添加数据源
    */
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        Object src = dataSource.getSource();
        if (src instanceof SolrClient sc) {
            this.client = sc;
        } else if (src instanceof String url) {
            this.client = new HttpSolrClient.Builder(url).build();
        }
        dataSources.put(name, (EngineDataSource<Object>) dataSource);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    @Override
    /**
     * 设置默认数据源名称
    */
    public Engine setDefaultDataSourceName(String name) {
        this.defaultDataSourceName = name;
        return this;
    }

    @Override
    /**
     * 存储
    */
    public <T> Engine store(String name, List<T> data) {
        if (data == null || data.isEmpty()) {
            return this;
        }
        SolrClient sc = getClient();
        if (sc == null) {
            throw new IllegalStateException("Solr 客户端未初始化，无法写入: " + name);
        }
        try {
            List<SolrInputDocument> docs = new ArrayList<>(data.size());
            for (T item : data) {
                SolrInputDocument doc = new SolrInputDocument();
                boolean hasId = false;
                for (var method : item.getClass().getMethods()) {
                    if (method.getParameterCount() == 0 && method.getName().startsWith("get")
                            && !method.getName().equals("getClass")) {
                        String prop = method.getName().substring(3);
                        String field = Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
                        Object value = ReflectUtils.invoke(item, method.getName(), Object.class);
                        if (value != null) {
                            doc.addField(field, value);
                            if (SolrFields.ID.equals(field)) {
                                hasId = true;
                            }
                        }
                    }
                }
                if (!hasId) {
                    doc.addField(SolrFields.ID, UUID.randomUUID().toString());
                }
                docs.add(doc);
            }
            sc.add(name, docs);
            sc.commit(name);
        } catch (Exception e) {
            throw new RuntimeException("Solr store 失败: collection=" + name, e);
        }
        return this;
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor(String dataSourceName) {
        return null;
    }

    @Override
    /**
     * 获取执行器
    */
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    /**
     * 获取Dialect
    */
    public Dialect getDialect(String dataSourceName) {
        return null;
    }

    @Override
    /**
     * 获取默认数据源名称
    */
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    @Override
    /**
     * Meta
    */
    public MetaData meta() {
        return new SolrMetaData(this);
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 SolrClient 失败", e);
            }
        }
        super.close();
    }

    @Override
    /**
     * 执行新查询
    */
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass, int limit, int offset) {
        SolrClient sc = getClient();
        if (sc == null) {
            throw new IllegalStateException("Solr 客户端未初始化，无法查询");
        }
        String collectionName = entityClass.getSimpleName().toLowerCase();
        try {
            org.apache.solr.client.solrj.SolrQuery query = new org.apache.solr.client.solrj.SolrQuery();
            if (where == null || where.isEmpty()) {
                query.setQuery("*:*");
            } else {
                query.setQuery(where);
            }
            if (params != null && params.length > 0) {
                for (Object param : params) {
                    query.addFilterQuery(String.valueOf(param));
                }
            }
            query.setStart(Math.max(0, offset));
            query.setRows(limit > 0 ? limit : 1000);
            QueryResponse response = sc.query(collectionName, query);
            SolrDocumentList docs = response.getResults();
            List<T> result = new ArrayList<>(docs.size());
            for (SolrDocument doc : docs) {
                result.add(documentToEntity(doc, entityClass));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Solr 查询失败: collection=" + collectionName
                    + " q=" + where, e);
        }
    }

    @Override
    /**
     * 执行更新
    */
    public <T> int executeUpdate(com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql<T> sql) {
        throw new UnsupportedOperationException(
                "Solr 引擎不支持 SQL 式更新，请使用 update(Class) 链式包装器");
    }

    @Override
    /**
     * 执行删除
    */
    public <T> int executeDelete(com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql<T> sql) {
        throw new UnsupportedOperationException(
                "Solr 引擎不支持 SQL 式删除，请使用 delete(Class) 链式包装器");
    }

    // ==================== ORM：Condition -> Solr 查询 ====================

    @Override
    /**
     * 查询
    */
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new LambdaQueryWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
            */
            protected String resolveColumn(SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            /**
             * 新instance
            */
            protected LambdaQueryWrapper<T> newInstance() {
                return new LambdaQueryWrapper<T>(entityClass) {
                    @Override
                    /**
                     * 解析Column
                    */
                    protected String resolveColumn(SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            /**
             * 列表
            */
            public List<T> list() {
                return search(entityClass, getConditions(), getOrderBys(),
                        getOffset(), getLimit() > 0 ? getLimit() : 1000).list();
            }

            @Override
            /**
             * One
            */
            public T one() {
                List<T> results = search(entityClass, getConditions(), getOrderBys(), 0, 1).list();
                if (results.isEmpty()) {
                    return null;
                }
                return results.getFirst();
            }

            @Override
            /**
             * Page
            */
            public Page<T> page(int pn, int ps) {
                SearchResult<T> sr = search(entityClass, getConditions(), getOrderBys(),
                        (pn - 1) * ps, ps);
                return new Page<>(pn, ps, sr.total(), sr.list());
            }
        };
    }

    /**
     * Solr 分组查询入口。
     * <p>将 GROUP BY 字段翻译为 Solr JSON Facet，返回每组 group_key 与 count。</p>
     *
     * @param entityClass 实体类
     * @param groupByCols 分组字段列表（至少一个）
     * @return 分组查询包装器
     */
    @SafeVarargs
    public final <T> GroupByQueryWrapper<T> groupBy(Class<T> entityClass, String... groupByCols) {
        return new GroupByQueryWrapper<>(this, entityClass, groupByCols);
    }

    // ==================== GROUP BY wrapper ====================

    public static final class GroupByQueryWrapper<T> {

        /**
         * 引擎
        */
        private final SolrEngine engine;
        /**
         * Entityclass
        */
        private final Class<T> entityClass;
        /**
         * 分组bycols
        */
        private final List<String> groupByCols = new ArrayList<>();
        /**
         * Where
        */
        private final List<String> where = new ArrayList<>();
        /**
         * 参数
        */
        private final List<Object> params = new ArrayList<>();
        /**
         * Selectcols
        */
        private final List<String> selectCols = new ArrayList<>();
        /**
         * 偏移
        */
        private int offset = 0;
        /**
         * 限制
        */
        private int limit = 1000;
        /**
         * 排序col
        */
        private String sortCol;
        /**
         * 排序asc
        */
        private boolean sortAsc = true;

        GroupByQueryWrapper(SolrEngine engine, Class<T> entityClass, String... groupByCols) {
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
         * 选择
         *
         * @param cols cols
         * @return 选择的结果
         */
        public GroupByQueryWrapper<T> select(String... cols) {
            selectCols.addAll(List.of(cols));
            return this;
        }

        /**
         * Eq
         *
         * @param col col
         * @param val val
         * @return eq的结果
         */
        public GroupByQueryWrapper<T> eq(String col, Object val) {
            where.add(escape(col) + ":" + escapeValue(val));
            return this;
        }

        /**
         * Ne
         *
         * @param col col
         * @param val val
         * @return ne的结果
         */
        public GroupByQueryWrapper<T> ne(String col, Object val) {
            where.add("-" + escape(col) + ":" + escapeValue(val));
            return this;
        }

        /**
         * Gt
         *
         * @param col col
         * @param val val
         * @return gt的结果
         */
        public GroupByQueryWrapper<T> gt(String col, Object val) {
            where.add(escape(col) + ":{" + escapeValue(val) + " TO *}");
            return this;
        }

        /**
         * Ge
         *
         * @param col col
         * @param val val
         * @return ge的结果
         */
        public GroupByQueryWrapper<T> ge(String col, Object val) {
            where.add(escape(col) + ":[" + escapeValue(val) + " TO *]");
            return this;
        }

        /**
         * Lt
         *
         * @param col col
         * @param val val
         * @return lt的结果
         */
        public GroupByQueryWrapper<T> lt(String col, Object val) {
            where.add(escape(col) + ":{* TO " + escapeValue(val) + "}");
            return this;
        }

        /**
         * Le
         *
         * @param col col
         * @param val val
         * @return le的结果
         */
        public GroupByQueryWrapper<T> le(String col, Object val) {
            where.add(escape(col) + ":[* TO " + escapeValue(val) + "]");
            return this;
        }

        /**
         * Like
         *
         * @param col col
         * @param pattern 模式
         * @return like的结果
         */
        public GroupByQueryWrapper<T> like(String col, String pattern) {
            where.add(escape(col) + ":" + likePattern(pattern));
            return this;
        }

        /**
         * 入
         *
         * @param col col
         * @param vals vals
         * @return 入的结果
         */
        public GroupByQueryWrapper<T> in(String col, Collection<?> vals) {
            if (vals == null || vals.isEmpty()) {
                where.add("-*:*");
                return this;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(escape(col)).append(":(");
            Iterator<?> it = vals.iterator();
            while (it.hasNext()) {
                sb.append(escapeValue(it.next()));
                if (it.hasNext()) {
                    sb.append(" ");
                }
            }
            sb.append(")");
            where.add(sb.toString());
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
         * 限制
         *
         * @param limit 限制
         * @return 限制的结果
         */
        public GroupByQueryWrapper<T> limit(int limit) {
            this.limit = Math.max(1, limit);
            return this;
        }

        /**
         * 偏移量
         *
         * @param offset 偏移量
         * @return 偏移量的结果
         */
        public GroupByQueryWrapper<T> offset(int offset) {
            this.offset = Math.max(0, offset);
            return this;
        }

        /**
         * 列表
         *
         * @return 列表的结果
         */
        public List<Map<String, Object>> list() {
            return executeGroupBy();
        }

        /**
         * Page
         *
         * @param pn pn
         * @param ps ps
         * @return page的结果
         */
        public Page<Map<String, Object>> page(int pn, int ps) {
            List<Map<String, Object>> all = executeGroupBy();
            int from = (pn - 1) * ps;
            int to = Math.min(from + ps, all.size());
            if (from >= all.size()) {
                return new Page<>(pn, ps, all.size(), Collections.emptyList());
            }
            return new Page<>(pn, ps, all.size(), all.subList(from, to));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        /**
         * 执行分组By
         *
         * @return 执行群体by的结果
         */
        private List<Map<String, Object>> executeGroupBy() {
            if (groupByCols.isEmpty()) {
                return Collections.emptyList();
            }
            SolrClient sc = engine.getClient();
            if (sc == null) {
                throw new IllegalStateException("Solr 客户端未初始化，无法执行 GROUP BY");
            }
            String collectionName = entityClass.getSimpleName().toLowerCase();
            try {
                String query = "*:*";
                if (!where.isEmpty()) {
                    query = String.join(" AND ", where);
                }
                String jsonFacet = buildJsonFacet(groupByCols, 0, offset, limit);
                org.apache.solr.client.solrj.SolrQuery sq = new org.apache.solr.client.solrj.SolrQuery();
                sq.setQuery(query);
                sq.setParam("json.facet", jsonFacet);
                sq.setStart(offset);
                sq.setRows(limit);
                if (sortCol != null && !sortCol.isEmpty()) {
                    sq.setSort(sortCol, sortAsc
                            ? org.apache.solr.client.solrj.SolrQuery.ORDER.asc
                            : org.apache.solr.client.solrj.SolrQuery.ORDER.desc);
                }
                org.apache.solr.client.solrj.response.QueryResponse response = sc.query(collectionName, sq);
                Object facetsObj = response.getResponse().get("facets");
                List<Map<String, Object>> result = parseFacetResponse(facetsObj, groupByCols, 0);
                return result != null ? result : Collections.emptyList();
            } catch (Exception e) {
                throw new RuntimeException("Solr GROUP BY 失败: collection=" + collectionName, e);
            }
        }
    }

    // ==================== GROUP BY helpers ====================

    @SuppressWarnings("unchecked")
    /**
     * 构建jsonfacet
     *
     * @param cols cols
     * @param idx idx
     * @param offset 偏移量
     * @param limit 限制
     * @return 构建jsonfacet的结果
     */
    private static String buildJsonFacet(List<String> cols, int idx, int offset, int limit) {
        if (idx >= cols.size()) {
            return "{\"count\":\"*\"}";
        }
        String col = cols.get(idx);
        if (col == null || !col.matches("[A-Za-z0-9_.\\-]+")) {
            throw new IllegalArgumentException("非法 GROUP BY 列名: " + col);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"").append(col).append("\":{");
        sb.append("\"type\":\"terms\",");
        sb.append("\"field\":\"").append(col).append("\",");
        sb.append("\"offset\":").append(idx == 0 ? offset : 0).append(",").append("\"limit\":").append(limit).append(",");
        sb.append("\"mincount\":1");
        if (idx < cols.size() - 1) {
            sb.append(",\"facet\":{").append(buildJsonFacet(cols, idx + 1, 0, limit)).append("}");
        }
        sb.append("}}");
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * 解析facet响应
     * @param facets facets
     * @param groupByCols 群体bycols
     * @param depth 深度
     * @param nf0 nf0
     * @param nf nf
     * @param groupByCols 群体bycols
     * @param children children
     * @param entityClass 实体类
     * @param col col
     * @param col col
     * @param q q
     * @param id 标识
     * @param value 值
     * @param newDoc 新doc
     * @param e e
     * @param e e
     * @param entityClass 实体类
     * @param col col
     * @param col col
     * @param query 查询
     * @param e e
     * @param e e
     * @param entityClass 实体类
     * @param conditions 条件
     * @param conditions 条件
     * @param 0 0
     * @param 1000 1000
     * @param list 列表
     * @param total total
     * @param entityClass 实体类
     * @param conditions 条件
     * @param start 启动
     * @param rows rows
     * @param 0 0
     * @param collectionName 集合名称
     * @param query 查询
     * @param start 启动
     * @param rows rows
     * @param solrQuery Solr查询
     * @param total total
     * @param doc doc
     * @param total total
     * @param e e
     * @param 0 0
     * @param conditions 条件
     * @param 0 0
     * @param c c
     * @param 0 0
     * @param value 值
     * @param value 值
     * @param sc sc
     * @param url url
     * @param value 值
     * @param targetType 目标类型
     * @param num num
     * @param str str
     * @param ignored ignored
     */
    private static List<Map<String, Object>> parseFacetResponse(
            Object facets, List<String> groupByCols, int depth) {
        /* solrj 对 /query 的响应中 facets 是普通 Map（非 NestableJsonFacet），
         * 旧实现 instanceof 判断永假导致永远返回空列表 */
        return parseFacetsFromMap(facets, groupByCols, depth);
    }

    /**
     * 以裸 映射/名称列表 结构递归解析 json.facet 分组结果。
     */
    private static List<Map<String, Object>> parseFacetsFromMap(
            Object facetsNode, List<String> groupByCols, int depth) {
        List<Map<String, Object>> result = new ArrayList<>();
        String col = groupByCols.get(depth);
        Object colFacet = child(facetsNode, col);
        if (colFacet == null) {
            return result;
        }
        Object bucketsObj = child(colFacet, "buckets");
        if (!(bucketsObj instanceof List)) {
            return result;
        }
        boolean isLeaf = depth == groupByCols.size() - 1;
        for (Object b : (List<?>) bucketsObj) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(col, child(b, "val"));
            if (isLeaf) {
                row.put("count", child(b, "count"));
            } else if (depth + 1 < groupByCols.size()) {
                row.put("children", parseFacetsFromMap(b, groupByCols, depth + 1));
            }
            result.add(row);
        }
        return result;
    }

    /**
     * 兼容 映射 与 名称列表 两种结构的取子节点
     *
     * @param node 节点
     * @param key 键
     * @return 子的结果
     */
    private static Object child(Object node, String key) {
        if (node instanceof Map) {
            return ((Map<?, ?>) node).get(key);
        }
        if (node instanceof org.apache.solr.common.util.NamedList) {
            return ((org.apache.solr.common.util.NamedList<?>) node).get(key);
        }
        return null;
    }

    @Override
    /**
     * 更新
    */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new LambdaUpdateWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
            */
            protected String resolveColumn(SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            /**
             * 新instance
            */
            protected LambdaUpdateWrapper<T> newInstance() {
                return new LambdaUpdateWrapper<T>(entityClass) {
                    @Override
                    /**
                     * 解析Column
                    */
                    protected String resolveColumn(SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            /**
             * 更新
            */
            public int update() {
                SolrClient sc = getClient();
                if (sc == null) {
                    throw new IllegalStateException("Solr 客户端未初始化，无法更新");
                }
                String collectionName = entityClass.getSimpleName().toLowerCase();
                String query = buildSolrQuery(getConditions());
                try {
                    org.apache.solr.client.solrj.SolrQuery q = new org.apache.solr.client.solrj.SolrQuery();
                    q.setQuery(query);
                    q.setSort(SolrFields.ID, org.apache.solr.client.solrj.SolrQuery.ORDER.asc);
                    q.setRows(UPDATE_BATCH);
                    q.setParam(org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_PARAM,
                            org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_START);
                    String cursorMark = org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_START;
                    Map<String, Object> setValues = getSetValues();
                    int updated = 0;
                    while (true) {
                        QueryResponse response = sc.query(collectionName, q);
                        SolrDocumentList docs = response.getResults();
                        for (SolrDocument doc : docs) {
                            Object id = doc.getFieldValue(SolrFields.ID);
                            if (id == null) {
                                continue;
                            }
                            SolrInputDocument newDoc = new SolrInputDocument();
                            newDoc.addField(SolrFields.ID, id);
                            for (String field : doc.getFieldNames()) {
                                if (SolrFields.ID.equals(field) || SolrFields.VERSION.equals(field)) {
                                    continue;
                                }
                                if (setValues.containsKey(field)) {
                                    newDoc.addField(field, setValues.get(field));
                                } else {
                                    Object value = doc.getFieldValue(field);
                                    if (value != null) {
                                        newDoc.addField(field, value);
                                    }
                                }
                            }
                            for (Map.Entry<String, Object> entry : setValues.entrySet()) {
                                String field = entry.getKey();
                                if (!doc.containsKey(field) && !SolrFields.ID.equals(field)) {
                                    newDoc.addField(field, entry.getValue());
                                }
                            }
                            sc.add(collectionName, newDoc);
                            updated++;
                        }
                        String nextCursor = response.getNextCursorMark();
                        if (docs.isEmpty() || nextCursor == null
                                || nextCursor.equals(cursorMark)) {
                            break;
                        }
                        cursorMark = nextCursor;
                        q.setParam(org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);
                    }
                    sc.commit(collectionName);
                    return updated;
                } catch (Exception e) {
                    throw new RuntimeException("Solr 更新失败: collection=" + collectionName
                            + " q=" + query, e);
                }
            }
        };
    }

    @Override
    /**
     * 删除
    */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new LambdaDeleteWrapper<T>(entityClass) {

            @Override
            /**
             * 解析Column
            */
            protected String resolveColumn(SFunction<T, ?> col) {
                return LambdaUtils.resolveObject(col);
            }

            @Override
            /**
             * 新instance
            */
            protected LambdaDeleteWrapper<T> newInstance() {
                return new LambdaDeleteWrapper<T>(entityClass) {
                    @Override
                    /**
                     * 解析Column
                    */
                    protected String resolveColumn(SFunction<T, ?> col) {
                        return LambdaUtils.resolveObject(col);
                    }
                };
            }

            @Override
            /**
             * 移除
            */
            public int remove() {
                SolrClient sc = getClient();
                if (sc == null) {
                    throw new IllegalStateException("Solr 客户端未初始化，无法删除");
                }
                String collectionName = entityClass.getSimpleName().toLowerCase();
                String query = buildSolrQuery(getConditions());
                try {
                    org.apache.solr.client.solrj.SolrQuery countQuery =
                            new org.apache.solr.client.solrj.SolrQuery(query);
                    countQuery.setRows(0);
                    long numFound = sc.query(collectionName, countQuery).getResults().getNumFound();
                    if (numFound > 0) {
                        sc.deleteByQuery(collectionName, query);
                        sc.commit(collectionName);
                    }
                    return (int) numFound;
                } catch (Exception e) {
                    throw new RuntimeException("Solr 删除失败: collection=" + collectionName
                            + " q=" + query, e);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    /**
     * 搜索
     *
     * @param entityClass 实体类
     * @param conditions 条件
     * @return 搜索的结果
     */
    private <T> List<T> search(Class<T> entityClass, List<Condition> conditions) {
        return search(entityClass, conditions, null, 0, 1000).list();
    }

    private record SearchResult<T>(List<T> list, long total) {
    }

    @SuppressWarnings("unchecked")
    /**
     * 搜索
     *
     * @param entityClass 实体类
     * @param conditions 条件
     * @param orderBys 排序列表（"col ASC"/"col DESC"），可为 null
     * @param start 启动
     * @param rows rows
     * @return 搜索的结果
     */
    private <T> SearchResult<T> search(Class<T> entityClass, List<Condition> conditions,
                                       List<String> orderBys, int start, int rows) {
        SolrClient sc = getClient();
        if (sc == null) {
            throw new IllegalStateException("Solr 客户端未初始化，无法查询");
        }
        String collectionName = entityClass.getSimpleName().toLowerCase();
        try {
            String query = buildSolrQuery(conditions);
            org.apache.solr.client.solrj.SolrQuery solrQuery = new org.apache.solr.client.solrj.SolrQuery();
            solrQuery.setQuery(query);
            solrQuery.setStart(start);
            solrQuery.setRows(rows);
            applySorts(solrQuery, orderBys);
            QueryResponse response = sc.query(collectionName, solrQuery);
            SolrDocumentList docs = response.getResults();
            long total = docs.getNumFound();
            List<T> result = new ArrayList<>(docs.size());
            for (SolrDocument doc : docs) {
                result.add(documentToEntity(doc, entityClass));
            }
            return new SearchResult<>(result, total);
        } catch (Exception e) {
            throw new RuntimeException("Solr 查询失败: collection=" + collectionName
                    + " q=" + buildSolrQuery(conditions), e);
        }
    }

    /**
     * 将 Solr 文档映射为实体对象。
     *
     * @param doc doc对象
     * @param entityClass 实体类
     * @param <T> T 泛型
     * @return 实体 对象
     */
    private <T> T documentToEntity(SolrDocument doc, Class<T> entityClass) {
        T instance = ReflectUtils.instantiate(entityClass);
        for (String field : doc.getFieldNames()) {
            Object value = doc.getFieldValue(field);
            if (value instanceof List<?> list && !list.isEmpty()) {
                value = list.getFirst();
            }
            String setterName = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            for (var method : entityClass.getMethods()) {
                if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                    ReflectUtils.invoke(instance, method.getName(), void.class,
                            convertValue(value, method.getParameterTypes()[0]));
                    break;
                }
            }
        }
        return instance;
    }

    /**
     * 应用包装器排序片段到 Solr 查询。
     *
     * @param solrQuery solr查询
     * @param orderBys 排序列表
     */
    private static void applySorts(org.apache.solr.client.solrj.SolrQuery solrQuery,
                                   List<String> orderBys) {
        if (orderBys == null || orderBys.isEmpty()) {
            return;
        }
        for (String orderBy : orderBys) {
            String[] parts = orderBy.trim().split("\\s+");
            org.apache.solr.client.solrj.SolrQuery.ORDER order =
                    parts.length > 1 && "DESC".equalsIgnoreCase(parts[1])
                            ? org.apache.solr.client.solrj.SolrQuery.ORDER.desc
                            : org.apache.solr.client.solrj.SolrQuery.ORDER.asc;
            solrQuery.addSort(parts[0], order);
        }
    }

    /**
     * 构建Solr查询
     *
     * @param conditions 条件
     * @return 构建Solr查询的结果
     */
    static String buildSolrQuery(List<Condition> conditions) {
        if (CollectionUtils.isEmpty(conditions)) {
            return "*:*";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append(buildConditionQuery(conditions.get(i)));
        }
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * 构建条件查询
     *
     * @param c c
     * @return 构建条件查询的结果
     */
    static String buildConditionQuery(Condition c) {
        if (c.isNested()) {
            List<Condition> nested = c.getNested();
            if (CollectionUtils.isEmpty(nested)) {
                return "*:*";
            }
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < nested.size(); i++) {
                if (i > 0) {
                    sb.append(" ").append(c.getNestedOperator()).append(" ");
                }
                sb.append(buildConditionQuery(nested.get(i)));
            }
            sb.append(")");
            return sb.toString();
        }

        String col = c.getColumnName();
        String op = c.getOperator();
        Object val = c.getValue();

        if (col == null) {
            throw new IllegalArgumentException("查询条件缺少列名: op=" + op);
        }

        switch (op) {
            case "=":
                return escape(col) + ":" + escapeValue(val);
            case "!=":
                return "-" + escape(col) + ":" + escapeValue(val);
            case ">":
                return escape(col) + ":{" + escapeValue(val) + " TO *}";
            case ">=":
                return escape(col) + ":[" + escapeValue(val) + " TO *]";
            case "<":
                return escape(col) + ":{* TO " + escapeValue(val) + "}";
            case "<=":
                return escape(col) + ":[* TO " + escapeValue(val) + "]";
            case "LIKE":
                return escape(col) + ":" + likePattern(val == null ? "*" : val.toString());
            case "NOT LIKE":
                return "-" + escape(col) + ":" + likePattern(val == null ? "*" : val.toString());
            case "IN": {
                Collection<?> values = (Collection<?>) val;
                if (CollectionUtils.isEmpty(values)) {
                    return "-*:*";
                }
                StringBuilder sb = new StringBuilder();
                sb.append(escape(col)).append(":(");
                Iterator<?> it = values.iterator();
                while (it.hasNext()) {
                    sb.append(escapeValue(it.next()));
                    if (it.hasNext()) {
                        sb.append(" ");
                    }
                }
                sb.append(")");
                return sb.toString();
            }
            case "NOT IN": {
                Collection<?> values = (Collection<?>) val;
                if (CollectionUtils.isEmpty(values)) {
                    return "*:*";
                }
                StringBuilder sb = new StringBuilder();
                sb.append("-").append(escape(col)).append(":(");
                Iterator<?> it = values.iterator();
                while (it.hasNext()) {
                    sb.append(escapeValue(it.next()));
                    if (it.hasNext()) {
                        sb.append(" ");
                    }
                }
                sb.append(")");
                return sb.toString();
            }
            case "IS NULL":
                return "-" + escape(col) + ":[* TO *]";
            case "IS NOT NULL":
                return "+" + escape(col) + ":[* TO *]";
            case "BETWEEN": {
                Object[] range = (Object[]) val;
                return escape(col) + ":[" + escapeValue(range[0]) + " TO " + escapeValue(range[1]) + "]";
            }
            default:
                throw new UnsupportedOperationException("Solr 不支持操作符: " + op);
        }
    }

    /**
     * Escape
     *
     * @param value 值
     * @return escape的结果
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\':
                case '+':
                case '-':
                case '!':
                case '(':
                case ')':
                case ':':
                case '^':
                case '[':
                case ']':
                case '\"':
                case '{':
                case '}':
                case '~':
                case '*':
                case '?':
                case '|':
                case '&':
                    sb.append('\\');
 // 下降 through
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * escape值
     *
     * @param value 值
     * @return escape值的结果
     */
    private static String escapeValue(Object value) {
        if (value == null) {
            return "\\*";
        }
        String str = value.toString();
        if (str.contains(" ")) {
            return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return escape(str);
    }

    /**
     * 将 SQL LIKE 模式转换为 Solr 通配符查询值。
     * <p>'%' → '*'，'_' → '?'，模式中出现的字面量 '*'/?'/'\' 及 Solr 特殊字符（含空格）
     * 均以反斜杠转义，保持通配符生效且不做隐式前后补 '*'。</p>
     *
     * @param pattern SQL LIKE 模式
     * @return Solr 通配符值（不含字段名）
     */
    static String likePattern(String pattern) {
        if (pattern == null) {
            return "\\*";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            switch (ch) {
                case '%':
                    sb.append('*');
                    break;
                case '_':
                    sb.append('?');
                    break;
                default:
                    if ("\\*?+-!(){}[]^\"~:&|;,#@/'. ".indexOf(ch) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 获取客户端
    */
    public com.chua.datasource.support.ddl.DslManager ddl() {
        return new com.chua.solr.support.ddl.SolrDdlManager(getClient());
    }

    /**
     * 获取客户端
     *
     * @return 获取客户端的结果
     */
    public SolrClient getClient() {
        if (client != null) {
            return client;
        }
        if (defaultDataSourceName == null) {
            return null;
        }
        EngineDataSource<?> eds = getDataSource(defaultDataSourceName);
        if (eds != null) {
            Object src = eds.getSource();
            if (src instanceof SolrClient sc) {
                return sc;
            }
            if (src instanceof String url) {
                client = new HttpSolrClient.Builder(url).build();
                return client;
            }
        }
        return null;
    }

    /**
     * 转换值
     *
     * @param value 值
     * @param targetType 目标类型
     * @return 转换值的结果
     */
    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        // 统一走 Converter 工具做类型转换，禁止手写逐类型分支（P3C 四十二）
        Object converted = Converter.convertIfNecessary(value, targetType);
        if (converted != null) {
            return converted;
        }
        return value;
    }
}
