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
import com.chua.common.support.reflection.ReflectUtils;
import redis.clients.jedis.util.SafeEncoder;

import java.lang.reflect.Method;
import java.util.*;

/**
* Redis redi搜索 引擎实现。
* <p>
* 继承 {@link RedisEngine}，基于 FT.搜索 实现实体查询、更新、删除，
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
    /** 添加数据源 */
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
        super.doAddDataSource(name, dataSource);
        return this;
    }

    @Override
    /** 设置默认数据源名称 */
    public Engine setDefaultDataSourceName(String name) {
        super.doSetDefaultDataSourceName(name);
        return this;
    }

    @Override
    /** 存储 */
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
                            Object value = ReflectUtils.invoke(item, method.getName(), Object.class);
                            if (value != null) {
                                hash.put(fieldName, value.toString());
                            }
                        } else if (method.getParameterCount() == 0 && method.getName().startsWith("is")
                                && (method.getReturnType() == Boolean.class || method.getReturnType() == boolean.class)) {
                            String propName = method.getName().substring(2);
                            String fieldName = Character.toLowerCase(propName.charAt(0)) + propName.substring(1);
                            Object value = ReflectUtils.invoke(item, method.getName(), Object.class);
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
    /** 获取执行器 */
    public SqlExecutor getExecutor(String dataSourceName) {
        return null;
    }

    @Override
    /** 获取执行器 */
    public SqlExecutor getExecutor() {
        return null;
    }

    @Override
    /**
    * 执行原生 Redis 命令。
    * <p>将语句按空白拆分为命令与参数（{@code params} 追加为尾部参数），
    * 经 Jedis {@code sendCommand} 分发执行；命令返回整数响应时返回该值，
    * 其余响应返回 0。</p>
    *
    * @param ql     Redis 命令行（如 {@code DEL user:1}）
    * @param params 附加参数列表
    * @return 受影响行数
     */
    public int execute(String ql, Object... params) {
        if (ql == null || ql.isBlank()) {
            throw new IllegalArgumentException("Redis 命令不能为空");
        }
        String[] tokens = ql.trim().split("\\s+");
        ProtocolCommand command = () -> SafeEncoder.encode(tokens[0].toUpperCase());
        List<byte[]> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            args.add(SafeEncoder.encode(tokens[i]));
        }
        if (params != null) {
            for (Object param : params) {
                args.add(SafeEncoder.encode(String.valueOf(param)));
            }
        }
        try (Jedis jedis = getPool(defaultDataSourceName).getResource()) {
            Object response = jedis.sendCommand(command, args.toArray(new byte[0][]));
            if (response instanceof Long affected) {
                return affected.intValue();
            }
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("Redis 命令执行失败: " + ql, e);
        }
    }

    @Override
    /** 获取数据源 */
    public <T> EngineDataSource<T> getDataSource(String name) {
        return super.getDataSource(name);
    }

    @Override
    /** 获取数据源 */
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
            /** 新instance */
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
            /** 列表 */
            public List<T> list() {
                return executeQuery(this, entityClass);
            }

            @Override
            /** One */
            public T one() {
                List<T> r = executeQuery(this, entityClass);
                return r.isEmpty() ? null : r.getFirst();
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
            /** 新instance */
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
            /** 新instance */
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
        private final RediSearchEngine engine;
        /** Entityclass */
        private final Class<T> entityClass;
        /** 分组bycols */
        private final List<String> groupByCols = new ArrayList<>();
        /** Where */
        private final List<String> where = new ArrayList<>();
        /** 排序col */
        private String sortCol;
        /** 排序asc */
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

        /**
        * Eq
        *
        * @param col col
        * @param val val
        * @return eq的结果
         */
        public GroupByQueryWrapper<T> eq(String col, Object val) {
            where.add("@" + escape(col) + ":[" + escapeValue(val) + " " + escapeValue(val) + "]");
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
            where.add("-@" + escape(col) + ":[" + escapeValue(val) + " " + escapeValue(val) + "]");
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
            where.add("@" + escape(col) + ":[(" + escapeValue(val) + " +inf]");
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
            where.add("@" + escape(col) + ":[" + escapeValue(val) + " +inf]");
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
            where.add("@" + escape(col) + ":[-inf (" + escapeValue(val) + "]");
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
            where.add("@" + escape(col) + ":[-inf " + escapeValue(val) + "]");
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

        /**
        * 入
        *
        * @param col col
        * @param vals vals
        * @return 入的结果
         */
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
    /** 获取默认数据源名称 */
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    @Override
    /** Meta */
    public MetaData meta() {
        return new RedisSearchMetaData(this);
    }

    // ==================== 新版 Wrapper ====================

    /**
    * 查询新
    *
    * @param entityClass 实体类
    * @return 查询新的结果
     */
    public <T> LambdaQueryWrapper<T> queryNew(Class<T> entityClass) {
        return new LambdaQueryWrapper<>(entityClass);
    }

    /**
    * 更新新
    *
    * @param entityClass 实体类
    * @return 更新新的结果
     */
    public <T> LambdaUpdateWrapper<T> updateNew(Class<T> entityClass) {
        return new LambdaUpdateWrapper<>(entityClass);
    }

    /**
    * 删除新
    *
    * @param entityClass 实体类
    * @return 删除新的结果
     */
    public <T> LambdaDeleteWrapper<T> deleteNew(Class<T> entityClass) {
        return new LambdaDeleteWrapper<>(entityClass);
    }

    /**
    * 执行
    *
    * @param wrapper 包装器
    * @return 执行的结果
     */
    public <T> List<T> execute(LambdaQueryWrapper<T> wrapper) {
        QuerySql<T> sql = wrapper.buildSql();
        return executeNewQuery(sql.whereClause(), sql.params().toArray(), wrapper.getEntityClass());
    }

    /**
    * 执行
    *
    * @param wrapper 包装器
    * @return 执行的结果
     */
    public int execute(LambdaUpdateWrapper<?> wrapper) {
        com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql<?> sql = wrapper.buildSql();
        return executeNewUpdate(sql.whereClause(), sql.params().toArray());
    }

    /**
    * 执行
    *
    * @param wrapper 包装器
    * @return 执行的结果
     */
    public int execute(LambdaDeleteWrapper<?> wrapper) {
        com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql<?> sql = wrapper.buildSql();
        return executeNewDelete(sql.whereClause(), sql.params().toArray());
    }

    // ==================== 旧版执行 ====================

    /**
    * 执行查询
    *
    * @param wrapper 包装器
    * @param entityClass 实体类
    * @return 执行查询的结果
     */
    private <T> List<T> executeQuery(LambdaQueryWrapper<T> wrapper, Class<T> entityClass) {
        QuerySql<T> sql = wrapper.buildSql();
        return executeNewQuery(sql.whereClause(), sql.params().toArray(), entityClass);
    }

    /**
    * 执行新查询
    *
    * @param whereClause whereclause
    * @param params 参数
    * @param entityClass 实体类
    * @return 执行新查询的结果
     */
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
    * @param wrapper 包装器
    * @param entityClass 实体类
    * @param pageNum pagenum
    * @param pageSize page大小
    * @param entityClass 实体类
    * @param total total
    * @param to 转为
    * @param pageSize page大小
    * @param total total
    * @param records records
    * @param wrapper 包装器
    * @param wrapper 包装器
    * @param sql SQL
    * @param params 参数
    * @param sql SQL
    * @param params 参数
    * @param index 索引
    * @param query 查询
    * @param response 响应
    * @param entityClass 实体类
    * @param jedis jedis
    * @param entityClass 实体类
    * @param index 索引
    * @param query 查询
    * @param groupByCols 群体bycols
    * @param sortCol 排序col
    * @param sortAsc 排序asc
    * @param offset 偏移量
    * @param limit 限制
    * @param response 响应
    * @param list 列表
    * @param listRow 列表row
    * @param val val
    * @param value 值
    * @param value 值
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

    /**
    * 执行更新
    *
    * @param wrapper 包装器
    * @return 执行更新的结果
     */
    private <T> int executeUpdate(LambdaUpdateWrapper<T> wrapper) {
        return 0;
    }

    /**
    * 执行删除
    *
    * @param wrapper 包装器
    * @return 执行删除的结果
     */
    private <T> int executeDelete(LambdaDeleteWrapper<T> wrapper) {
        return 0;
    }

    /**
    * 执行新更新
    *
    * @param sql SQL
    * @param params 参数
    * @return 执行新更新的结果
     */
    private int executeNewUpdate(String sql, Object[] params) {
        return 0;
    }

    /**
    * 执行新删除
    *
    * @param sql SQL
    * @param params 参数
    * @return 执行新删除的结果
     */
    private int executeNewDelete(String sql, Object[] params) {
        return 0;
    }

    /** Ft_搜索 */
    private static final ProtocolCommand FT_SEARCH = () -> SafeEncoder.encode("FT.SEARCH");

    /**
    * 构建Ft搜索参数
    *
    * @param index 索引
    * @param query 查询
    * @return 构建ft搜索参数的结果
     */
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
    /**
    * 映射ft搜索响应
    *
    * @param response 响应
    * @param entityClass 实体类
    * @param jedis jedis
    * @return 映射ft搜索响应的结果
     */
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
    * 构建ftaggregate参数
    * @param index 索引
    * @param query 查询
    * @param groupByCols 群体bycols
    * @param sortCol 排序col
    * @param sortAsc 排序asc
    * @param offset 偏移量
    * @param limit 限制
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
    /**
    * 解析aggregate响应
    *
    * @param response 响应
    * @return 解析aggregate响应的结果
     */
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
        return value.replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(".", "\\.")
                .replace("-", "\\-")
                .replace(" ", "\\ ");
    }

    /**
    * escape值
    *
    * @param value 值
    * @return escape值的结果
     */
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
