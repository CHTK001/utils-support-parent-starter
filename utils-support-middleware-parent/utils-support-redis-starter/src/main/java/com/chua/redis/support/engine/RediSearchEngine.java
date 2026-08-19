package com.chua.redis.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.QuerySql;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.common.support.lang.datasource.meta.MetaData;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.wrapper.toolkit.LambdaUtils;
import com.chua.redis.support.meta.RedisSearchMetaData;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.util.SafeEncoder;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Redis RediSearch 引擎实现。
 * <p>
 * 继承 {@link RedisEngine}，基于 FT.SEARCH 实现实体查询、更新、删除，
 * 并支持全量扫描回退机制。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("redis")
public class RediSearchEngine extends RedisEngine implements Engine {

    @Override
    /** 添加DataSource */
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        super.doAddDataSource(name, dataSource);
        return this;
    }

    @Override
    /** 设置DefaultDataSourceName */
    public Engine setDefaultDataSourceName(String name) {
        super.doSetDefaultDataSourceName(name);
        return this;
    }

    @Override
    /** Store */
    public <T> Engine store(String name, List<T> data) {
        String keyPrefix = name != null ? name.toLowerCase() : "default";
        try (Jedis jedis = getPool(defaultDataSourceName).getResource()) {
            for (int i = 0; i < data.size(); i++) {
                T item = data.get(i);
                String key = keyPrefix + ":" + i;
                Map<String, String> hash = new LinkedHashMap<>();
                try {
                    for (Method method : item.getClass().getMethods()) {
                        if (method.getParameterCount() == 0 && method.getName().startsWith("get")
                                && !method.getName().equals("getClass")) {
                            String propName = method.getName().substring(3);
                            String fieldName = Character.toLowerCase(propName.charAt(0)) + propName.substring(1);
                            Object value = method.invoke(item);
                            if (value != null) {
                                hash.put(fieldName, value.toString());
                            }
                        } else if (method.getParameterCount() == 0 && method.getName().startsWith("is")
                                && (method.getReturnType() == Boolean.class || method.getReturnType() == boolean.class)) {
                            String propName = method.getName().substring(2);
                            String fieldName = Character.toLowerCase(propName.charAt(0)) + propName.substring(1);
                            Object value = method.invoke(item);
                            if (value != null) {
                                hash.put(fieldName, value.toString());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("存储实体失败: " + e.getMessage());
                }
                jedis.hmset(key, hash);
            }
        } catch (Exception e) {
            log.warn("Redis store失败: " + e.getMessage());
        }
        return this;
    }

    @Override
    /** 获取Executor */
    public SqlExecutor getExecutor(String dataSourceName) {
        return null;
    }

    @Override
    /** 获取Executor */
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    /** 获取DataSource */
    public <T> EngineDataSource<T> getDataSource(String name) {
        return super.getDataSource(name);
    }

    @Override
    /** 获取DataSource */
    public <T> EngineDataSource<T> getDataSource() {
        return super.getDataSource();
    }

    @Override
    /** 查询 */
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new LambdaQueryWrapper<T>(entityClass) {
            @Override
            /** 解析Column */
            protected String resolveColumn(SFunction<T, ?> column) {
                return LambdaUtils.resolveObject(column);
            }

            @Override
            /** NewInstance */
            protected LambdaQueryWrapper<T> newInstance() {
                LambdaQueryWrapper<T> sub = new LambdaQueryWrapper<T>(entityClass) {
                    @Override
                    /** 解析Column */
                    protected String resolveColumn(SFunction<T, ?> column) {
                        return LambdaUtils.resolveObject(column);
                    }
                };
                return sub;
            }

            @Override
            /** List */
            public List<T> list() {
                return executeQuery(this, entityClass);
            }

            @Override
            /** One */
            public T one() {
                List<T> r = executeQuery(this, entityClass);
                return r.isEmpty() ? null : r.get(0);
            }

            @Override
            /** Page */
            public Page<T> page(int pageNum, int pageSize) {
                return executePage(this, entityClass, pageNum, pageSize);
            }
        };
    }

    @Override
    /** 更新 */
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new LambdaUpdateWrapper<T>(entityClass) {
            @Override
            /** 解析Column */
            protected String resolveColumn(SFunction<T, ?> column) {
                return LambdaUtils.resolveObject(column);
            }

            @Override
            /** NewInstance */
            protected LambdaUpdateWrapper<T> newInstance() {
                return new LambdaUpdateWrapper<T>(entityClass) {
                    @Override
                    /** 解析Column */
                    protected String resolveColumn(SFunction<T, ?> column) {
                        return LambdaUtils.resolveObject(column);
                    }
                };
            }

            @Override
            /** 更新 */
            public int update() {
                return executeUpdate(this);
            }
        };
    }

    @Override
    /** 删除 */
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new LambdaDeleteWrapper<T>(entityClass) {
            @Override
            /** 解析Column */
            protected String resolveColumn(SFunction<T, ?> column) {
                return LambdaUtils.resolveObject(column);
            }

            @Override
            /** NewInstance */
            protected LambdaDeleteWrapper<T> newInstance() {
                return new LambdaDeleteWrapper<T>(entityClass) {
                    @Override
                    /** 解析Column */
                    protected String resolveColumn(SFunction<T, ?> column) {
                        return LambdaUtils.resolveObject(column);
                    }
                };
            }

            @Override
            /** 移除 */
            public int remove() {
                return executeDelete(this);
            }
        };
    }

    @SafeVarargs
    /** 分组By */
    public final <T> GroupByQueryWrapper<T> groupBy(Class<T> entityClass, String... groupByCols) {
        return new GroupByQueryWrapper<>(this, entityClass, groupByCols);
    }

    // ==================== GROUP BY wrapper ====================

    public static final class GroupByQueryWrapper<T> {

        /** 引擎 */
        private final RediSearchEngine engine;
        /** Entityclass */
        private final Class<T> entityClass;
        /** 分组BYcols */
        private final List<String> groupByCols = new ArrayList<>();
        /** Where */
        private final List<String> where = new ArrayList<>();
        /** SortCOL */
        private String sortCol;
        /** SortASC */
        private boolean sortAsc = true;
        /** 偏移 */
        private int offset = 0;
        /** 限制 */
        private int limit = 1000;

        GroupByQueryWrapper(RediSearchEngine engine, Class<T> entityClass, String... groupByCols) {
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

        /** Eq */
        public GroupByQueryWrapper<T> eq(String col, Object val) {
            where.add("@" + escape(col) + ":[" + escapeValue(val) + " " + escapeValue(val) + "]");
            return this;
        }

        /** Ne */
        public GroupByQueryWrapper<T> ne(String col, Object val) {
            where.add("-@" + escape(col) + ":[" + escapeValue(val) + " " + escapeValue(val) + "]");
            return this;
        }

        /** Gt */
        public GroupByQueryWrapper<T> gt(String col, Object val) {
            where.add("@" + escape(col) + ":[(" + escapeValue(val) + " +inf]");
            return this;
        }

        /** Ge */
        public GroupByQueryWrapper<T> ge(String col, Object val) {
            where.add("@" + escape(col) + ":[" + escapeValue(val) + " +inf]");
            return this;
        }

        /** Lt */
        public GroupByQueryWrapper<T> lt(String col, Object val) {
            where.add("@" + escape(col) + ":[-inf (" + escapeValue(val) + "]");
            return this;
        }

        /** Le */
        public GroupByQueryWrapper<T> le(String col, Object val) {
            where.add("@" + escape(col) + ":[-inf " + escapeValue(val) + "]");
            return this;
        }

        /** Like */
        public GroupByQueryWrapper<T> like(String col, String pattern) {
            String p = pattern;
            if (p.contains("%")) {
                p = p.replace("%", "*");
            }
            if (!p.startsWith("*")) {
                p = "*" + p;
            }
            if (!p.endsWith("*")) {
                p = p + "*";
            }
            where.add("@" + escape(col) + ":" + escapeValue(p));
            return this;
        }

        /** In */
        public GroupByQueryWrapper<T> in(String col, Collection<?> vals) {
            StringBuilder sb = new StringBuilder();
            sb.append("@").append(escape(col)).append(":(");
            Iterator<?> it = vals.iterator();
            while (it.hasNext()) {
                sb.append(escapeValue(it.next()));
                if (it.hasNext()) {
                    sb.append("|");
                }
            }
            sb.append(")");
            where.add(sb.toString());
            return this;
        }

        /** OrderBy */
        public GroupByQueryWrapper<T> orderBy(String col, boolean asc) {
            this.sortCol = col;
            this.sortAsc = asc;
            return this;
        }

        /** Limit */
        public GroupByQueryWrapper<T> limit(int limit) {
            this.limit = Math.max(1, limit);
            return this;
        }

        /** Offset */
        public GroupByQueryWrapper<T> offset(int offset) {
            this.offset = Math.max(0, offset);
            return this;
        }

        /** List */
        public List<Map<String, Object>> list() {
            return executeGroupBy();
        }

        /** Page */
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
        /** 执行分组By */
        private List<Map<String, Object>> executeGroupBy() {
            try (Jedis jedis = engine.getPool(engine.getDefaultDataSourceName()).getResource()) {
                String indexName = "idx:" + engine.getKeyPrefix(entityClass);
                String query = "*:*";
                if (!where.isEmpty()) {
                    query = String.join(" ", where);
                }

                byte[][] args = buildFtAggregateArgs(indexName, query, groupByCols, sortCol, sortAsc, offset, limit);
                Object response = jedis.sendCommand(FT_AGGREGATE, args);
                return parseAggregateResponse(response);
            } catch (Exception e) {
                log.warn("Redis GROUP BY 失败: " + e.getMessage(), e);
                return Collections.emptyList();
            }
        }
    }

    @Override
    /** 获取Dialect */
    public Dialect getDialect(String dataSourceName) {
        return null;
    }

    @Override
    /** 关闭 */
    public void close() {
        super.close();
    }

    @Override
    /** 获取DefaultDataSourceName */
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    @Override
    /** Meta */
    public MetaData meta() {
        return new RedisSearchMetaData(this);
    }

    // ==================== 新版 Wrapper ====================

    /** 查询New */
    public <T> LambdaQueryWrapper<T> queryNew(Class<T> entityClass) {
        return new LambdaQueryWrapper<>(entityClass);
    }

    /** 更新New */
    public <T> LambdaUpdateWrapper<T> updateNew(Class<T> entityClass) {
        return new LambdaUpdateWrapper<>(entityClass);
    }

    /** 删除New */
    public <T> LambdaDeleteWrapper<T> deleteNew(Class<T> entityClass) {
        return new LambdaDeleteWrapper<>(entityClass);
    }

    /** 执行 */
    public <T> List<T> execute(LambdaQueryWrapper<T> wrapper) {
        QuerySql<T> sql = wrapper.buildSql();
        return executeNewQuery(sql.whereClause(), sql.params().toArray(), wrapper.getEntityClass());
    }

    /** 执行 */
    public int execute(LambdaUpdateWrapper<?> wrapper) {
        com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql<?> sql = wrapper.buildSql();
        return executeNewUpdate(sql.whereClause(), sql.params().toArray());
    }

    /** 执行 */
    public int execute(LambdaDeleteWrapper<?> wrapper) {
        com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql<?> sql = wrapper.buildSql();
        return executeNewDelete(sql.whereClause(), sql.params().toArray());
    }

    // ==================== 旧版执行 ====================

    /** 执行查询 */
    private <T> List<T> executeQuery(LambdaQueryWrapper<T> wrapper, Class<T> entityClass) {
        QuerySql<T> sql = wrapper.buildSql();
        return executeNewQuery(sql.whereClause(), sql.params().toArray(), entityClass);
    }

    /** 执行New查询 */
    private <T> List<T> executeNewQuery(String whereClause, Object[] params, Class<T> entityClass) {
        String keyPrefix = getKeyPrefix(entityClass);
        try (Jedis jedis = getPool(defaultDataSourceName).getResource()) {
            String indexName = "idx:" + keyPrefix;
            List<Object> paramList = params != null ? Arrays.asList(params) : Collections.emptyList();
            String query = new RediSearchQueryConverter().convertToQuery(whereClause, paramList);
            try {
                byte[][] rawArgs = buildFtSearchArgs(indexName, query);
                Object response = jedis.sendCommand(FT_SEARCH, rawArgs);
                return mapFtSearchResponse(response, entityClass, jedis);
            } catch (Exception e) {
                log.warn("FT.SEARCH失败: " + e.getMessage() + ", 回退到全量扫描");
                return scanAll(jedis, keyPrefix, entityClass);
            }
        }
    }

    /**
     * 执行Page
     * @param wrapper wrapper
     * @param entityClass entityClass
     * @param pageNum pageNum
     * @param pageSize pageSize
     * @param entityClass entityClass
     * @param total total
     * @param to to
     * @param pageSize pageSize
     * @param total total
     * @param records records
     * @param wrapper wrapper
     * @param wrapper wrapper
     * @param sql sql
     * @param params params
     * @param sql sql
     * @param params params
     * @param index index
     * @param query query
     * @param response response
     * @param entityClass entityClass
     * @param jedis jedis
     * @param entityClass entityClass
     * @param index index
     * @param query query
     * @param groupByCols groupByCols
     * @param sortCol sortCol
     * @param sortAsc sortAsc
     * @param offset offset
     * @param limit limit
     * @param response response
     * @param list list
     * @param listRow listRow
     * @param val val
     * @param value value
     * @param value value
     */
    private <T> Page<T> executePage(
            LambdaQueryWrapper<T> wrapper,
            Class<T> entityClass, int pageNum, int pageSize) {
        List<T> all = executeQuery(wrapper, entityClass);
        int total = all.size();
        int from = (pageNum - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        List<T> records = from >= total ? Collections.emptyList() : all.subList(from, to);
        return new Page<>(pageNum, pageSize, total, records);
    }

    /** 执行更新 */
    private <T> int executeUpdate(LambdaUpdateWrapper<T> wrapper) {
        return 0;
    }

    /** 执行删除 */
    private <T> int executeDelete(LambdaDeleteWrapper<T> wrapper) {
        return 0;
    }

    /** 执行New更新 */
    private int executeNewUpdate(String sql, Object[] params) {
        return 0;
    }

    /** 执行New删除 */
    private int executeNewDelete(String sql, Object[] params) {
        return 0;
    }

    /** Ft_search */
    private static final ProtocolCommand FT_SEARCH = () -> SafeEncoder.encode("FT.SEARCH");

    /** 构建Ft搜索Args */
    private byte[][] buildFtSearchArgs(String index, String query) {
        return new byte[][]{
                SafeEncoder.encode(index),
                SafeEncoder.encode(query),
                SafeEncoder.encode("LIMIT"),
                SafeEncoder.encode("0"),
                SafeEncoder.encode("1000")
        };
    }

    @SuppressWarnings("unchecked")
    /** MapFt搜索Response */
    private <T> List<T> mapFtSearchResponse(Object response, Class<T> entityClass, Jedis jedis) {
        List<T> result = new ArrayList<>();
        if (response == null) {
            return result;
        }
        String respStr = response.toString();
        String[] lines = respStr.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String key = lines[i].trim();
            if (key.isEmpty()) {
                continue;
            }
            Map<String, String> hash = jedis.hgetAll(key);
            if (!hash.isEmpty()) {
                result.add(mapToEntity(hash, entityClass));
            }
        }
        return result;
    }

    /** Ft_aggregate */
    private static final ProtocolCommand FT_AGGREGATE = () -> SafeEncoder.encode("FT.AGGREGATE");

    /**
     * 构建FtAggregateArgs
     * @param index index
     * @param query query
     * @param groupByCols groupByCols
     * @param sortCol sortCol
     * @param sortAsc sortAsc
     * @param offset offset
     * @param limit limit
     */
    private static byte[][] buildFtAggregateArgs(String index, String query, List<String> groupByCols,
                                                  String sortCol, boolean sortAsc, int offset, int limit) {
        List<byte[]> args = new ArrayList<>();
        args.add(SafeEncoder.encode(index));
        args.add(SafeEncoder.encode(query));
        if (groupByCols != null && !groupByCols.isEmpty()) {
            args.add(SafeEncoder.encode("GROUPBY"));
            args.add(SafeEncoder.encode(String.valueOf(groupByCols.size())));
            for (String col : groupByCols) {
                args.add(SafeEncoder.encode(col));
            }
            args.add(SafeEncoder.encode("REDUCE"));
            args.add(SafeEncoder.encode("COUNT"));
            args.add(SafeEncoder.encode("0"));
            args.add(SafeEncoder.encode("AS"));
            args.add(SafeEncoder.encode("count"));
        }
        if (sortCol != null && !sortCol.isEmpty()) {
            args.add(SafeEncoder.encode("SORTBY"));
            args.add(SafeEncoder.encode("1"));
            args.add(SafeEncoder.encode(sortCol));
            args.add(SafeEncoder.encode(sortAsc ? "ASC" : "DESC"));
        }
        args.add(SafeEncoder.encode("LIMIT"));
        args.add(SafeEncoder.encode(String.valueOf(offset)));
        args.add(SafeEncoder.encode(String.valueOf(limit)));
        return args.toArray(new byte[][]{});
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /** 解析AggregateResponse */
    private static List<Map<String, Object>> parseAggregateResponse(Object response) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (response == null) {
            return result;
        }
        List<Object> rows;
        if (response instanceof List<?> list) {
            rows = new ArrayList<>(list);
        } else {
            return result;
        }
        for (Object rowObj : rows) {
            List<String> kv;
            if (rowObj instanceof List<?> listRow) {
                kv = new ArrayList<>();
                for (Object o : listRow) {
                    kv.add(o.toString());
                }
            } else {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < kv.size(); i += 2) {
                if (i + 1 < kv.size()) {
                    String key = kv.get(i);
                    String val = kv.get(i + 1);
                    if ("count".equals(key)) {
                        row.put(key, Long.parseLong(val));
                    } else {
                        row.put(key, val);
                    }
                }
            }
            result.add(row);
        }
        return result;
    }

    /** Escape */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(".", "\\.")
                .replace("-", "\\-")
                .replace(" ", "\\ ");
    }

    /** EscapeValue */
    private static String escapeValue(Object value) {
        if (value == null) {
            return "";
        }
        String str = value.toString();
        if (str.contains(" ") || str.contains("|") || str.contains("-") || str.contains("(") || str.contains(")")) {
            return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return escape(str);
    }
}
