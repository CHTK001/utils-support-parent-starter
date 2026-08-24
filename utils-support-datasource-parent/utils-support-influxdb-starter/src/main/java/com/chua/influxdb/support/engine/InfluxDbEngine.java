package com.chua.influxdb.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.AbstractEngine;
import com.chua.influxdb.support.datasource.InfluxDbEngineDataSource;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * InfluxDB 时序数据库引擎实现（真实 HTTP 客户端）。
 * <p>
 * <b>写入</b>经官方 SDK 行协议 {@code write(Point)}；
 * <b>查询/删除</b>经 InfluxQL 下推服务器执行。SPI 键 {@code "influxdb"}。
 * </p>
 * 时序库语义：不支持 UPDATE（相同 tag+时间戳重写即覆盖）；DELETE 需命中 time 条件。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("influxdb")
public class InfluxDbEngine extends AbstractEngine {

    /**
     * 缺省数据库名
     */
    private static final String DEFAULT_DATABASE = "app";

    /**
     * 缺省保留策略
     */
    private static final String DEFAULT_RETENTION = "autogen";

    /**
     * 实体字段映射缓存：类 -> (snake_case 列名 -> Field)
     */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 添加数据源（InfluxDB 客户端或连接地址）。
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
        InfluxDB client;
        if (source instanceof InfluxDB influx) {
            client = influx;
        } else if (source instanceof String url) {
            client = hasText(dataSource.username())
                    ? InfluxDBFactory.connect(url, dataSource.username(), dataSource.password())
                    : InfluxDBFactory.connect(url);
        } else {
            throw new IllegalArgumentException("InfluxDbEngine 仅支持 InfluxDB 客户端或 URL 字符串");
        }
        String db = dataSource.database() == null || dataSource.database().isEmpty()
                ? DEFAULT_DATABASE : dataSource.database();
        client.setDatabase(db);
        dataSources.put(name, (EngineDataSource<Object>) (Object)
                new InfluxDbEngineDataSource(name, dataSource.url(),
                        dataSource.username(), dataSource.password(), db, client));
        if (defaultDataSourceName == null || defaultDataSourceName.equals(name)) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 便捷添加数据源。
     *
     * @param name      数据源名称
     * @param url       连接地址，如 {@code http://172.16.0.40:8086}
     * @param database  数据库名
     * @param username  用户名，可为空
     * @param password  密码，可为空
     * @return this
     */
    public InfluxDbEngine addDataSource(String name, String url,
                                        String database, String username, String password) {
        dataSources.put(name, wrapUrlDataSource(name, url, database, username, password));
        if (defaultDataSourceName == null || defaultDataSourceName.equals(name)) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 内部包装：按连接串创建客户端的数据源。
     */
    private EngineDataSource<Object> wrapUrlDataSource(String name, String url,
                                                       String database, String username, String password) {
        InfluxDB client = hasText(username)
                ? InfluxDBFactory.connect(url, username, password)
                : InfluxDBFactory.connect(url);
        String db = database == null || database.isEmpty() ? DEFAULT_DATABASE : database;
        client.setDatabase(db);
        return (EngineDataSource<Object>) (Object)
                new InfluxDbEngineDataSource(name, url, username, password, db, client);
    }

    /**
     * 判断字符串非空。
     */
    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * 获取默认 InfluxDB 客户端；默认数据源缺失时回退任意已注册数据源。
     *
     * @return InfluxDB 客户端
     */
    public InfluxDB client() {
        EngineDataSource<Object> ds = defaultDataSourceName == null
                ? null : dataSources.get(defaultDataSourceName);
        if (ds == null && !dataSources.isEmpty()) {
            ds = dataSources.values().iterator().next();
        }
        Object raw = ds;
        if (!(raw instanceof InfluxDbEngineDataSource ids)) {
            throw new IllegalStateException("未配置 InfluxDB 数据源");
        }
        return ids.getSource();
    }

    // ==================== 写入（行协议） ====================

    /**
     * 写入一个数据点（异步批量）。
     *
     * @param point SDK 数据点
     * @return this
     */
    public InfluxDbEngine write(Point point) {
        client().write(point);
        return this;
    }

    /**
     * 以当前时间写入一条记录。
     *
     * @param measurement 表(measurement)名
     * @param tags        tag 集
     * @param fields      field 集
     * @return this
     */
    public InfluxDbEngine write(String measurement, Map<String, String> tags, Map<String, Object> fields) {
        Point.Builder b = Point.measurement(measurement).time(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        if (tags != null) {
            tags.forEach(b::tag);
        }
        if (fields != null) {
            fields.forEach((k, v) -> {
                if (v instanceof Double d) {
                    b.addField(k, d);
                } else if (v instanceof Float f) {
                    b.addField(k, f);
                } else if (v instanceof Long l) {
                    b.addField(k, l);
                } else if (v instanceof Integer i) {
                    b.addField(k, i);
                } else if (v instanceof Boolean bo) {
                    b.addField(k, bo);
                } else if (v instanceof Number n) {
                    b.addField(k, n);
                } else {
                    b.addField(k, String.valueOf(v));
                }
            });
        }
        return write(b.build());
    }

    /**
     * 获取当前数据源数据库名，缺省 {@value #DEFAULT_DATABASE}。
     *
     * @return 数据库名
     */
    private String database() {
        EngineDataSource<Object> ds = defaultDataSourceName == null
                ? null : dataSources.get(defaultDataSourceName);
        String db = ds == null ? null : ds.database();
        return db == null || db.isEmpty() ? DEFAULT_DATABASE : db;
    }

    // ==================== 查询（InfluxQL 下推） ====================

    /**
     * WHERE / 查询全部下推为 InfluxQL；排序由父类在结果集上完成。
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> List<T> executeQuery(com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper<T> wrapper,
                                    Class<T> entityClass) {
        com.chua.common.support.lang.datasource.engine.wrapper.QuerySql<T> sql =
                (com.chua.common.support.lang.datasource.engine.wrapper.QuerySql<T>) wrapper.buildSql();
        String where = sql.whereClause() == null ? "" : sql.whereClause().trim();
        List<T> rows = doQuery(entityClass, normalizeColumns(where, entityClass), sql.params());
        return sortRows(rows, sql.orderBys(), entityClass);
    }

    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] params, Class<T> entityClass) {
        List<T> rows = doQuery(entityClass,
                normalizeColumns(where == null ? "" : where.trim(), entityClass),
                params == null ? List.of() : List.of(params));
        return rows;
    }

    /**
     * 执行 SELECT 并映射实体。
     */
    private <T> List<T> doQuery(Class<T> entityClass, String where, List<Object> params) {
        String measurement = getTableName(entityClass);
        StringBuilder ql = new StringBuilder("SELECT * FROM \"").append(measurement).append('"');
        if (!where.isEmpty()) {
            ql.append(" WHERE ").append(inline(where, params));
        }
        QueryResult resp = client().query(new Query(ql.toString(), database()));
        return mapResult(entityClass, resp);
    }

    /**
     * 占位符替换为 InfluxQL 字面量。
     */
    private String inline(String where, List<Object> params) {
        if (params == null || params.isEmpty()) {
            return where;
        }
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (int i = 0; i < where.length(); i++) {
            char ch = where.charAt(i);
            if (ch == '?' && idx < params.size()) {
                sb.append(literal(params.get(idx++)));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 参数转 InfluxQL 字面量（字符串单引号并转义）。
     */
    private String literal(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return "'" + v.toString().replace("'", "\\'") + "'";
    }

    /**
     * 解析 QueryResult 并映射为实体列表。
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> mapResult(Class<T> entityClass, QueryResult resp) {
        List<T> out = new ArrayList<>();
        if (resp == null || resp.getResults() == null) {
            return out;
        }
        Map<String, Field> fields = fieldsOf(entityClass);
        for (QueryResult.Result r : resp.getResults()) {
            if (r.getSeries() == null) {
                continue;
            }
            for (QueryResult.Series s : r.getSeries()) {
                List<String> cols = s.getColumns();
                for (List<Object> row : s.getValues()) {
                    try {
                        T inst = entityClass.getDeclaredConstructor().newInstance();
                        for (int i = 0; i < cols.size() && i < row.size(); i++) {
                            Field f = fields.get(cols.get(i));
                            if (f == null || row.get(i) == null) {
                                continue;
                            }
                            f.setAccessible(true);
                            f.set(inst, convert(row.get(i), f.getType()));
                        }
                        out.add(inst);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("InfluxDB 行映射失败: " + entityClass.getName(), e);
                    }
                }
            }
        }
        return out;
    }

    // ==================== 删除（InfluxQL 下推） ====================

    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        String where = sql.whereClause();
        if (where == null || where.isBlank()) {
            throw new IllegalStateException("DELETE 必须携带 WHERE 条件（InfluxQL 需命中 time/tag 列）");
        }
        String table = getTableName(sql.entityClass());
        // 豁免：表名由实体类名派生(getTableName)，WHERE 由框架 LambdaQueryWrapper 解析生成，参数经 literal() 转义
        String full = inline("DELETE FROM \"" + table + "\" WHERE "
                + normalizeColumns(where, sql.entityClass()), sql.params());
        client().query(new Query(full, database()));
        // InfluxQL 不返回影响行数
        return 0;
    }

    // ==================== 明确不支持的语义 ====================

    /**
     * InfluxDB 无 UPDATE：相同 tag+时间戳重写即覆盖。
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        throw new UnsupportedOperationException(
                "InfluxDB 为时序库，不支持 UPDATE。请以相同 tag + 时间戳重新 write() 覆盖。");
    }

    /**
     * 数据一律经 write(Point) 真实落库，禁止内存旁路。
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        throw new UnsupportedOperationException(
                "InfluxDbEngine 不支持内存存储，请使用 write(Point) 写入真库。");
    }

    // ==================== 工具方法 ====================

    /**
     * 结果集按 orderBys 排序（列名已归一化到字段）。
     */
    private <T> List<T> sortRows(List<T> rows, List<String> orderBys, Class<?> entityClass) {
        if (orderBys == null || orderBys.isEmpty() || rows.size() < 2) {
            return rows;
        }
        Map<String, Field> fields = fieldsOf(entityClass);
        List<T> copy = new ArrayList<>(rows);
        copy.sort((a, b) -> {
            for (String ob : orderBys) {
                String[] parts = ob.trim().split("\\s+", 2);
                Field f = fields.get(parts[0]);
                boolean desc = parts.length > 1 && "DESC".equalsIgnoreCase(parts[1]);
                if (f == null) {
                    continue;
                }
                int cmp = compare(a, b, f);
                if (cmp != 0) {
                    return desc ? -cmp : cmp;
                }
            }
            return 0;
        });
        return copy;
    }

    /**
     * 反射比较两对象某字段值。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> int compare(T a, T b, Field f) {
        try {
            f.setAccessible(true);
            Object va = f.get(a);
            Object vb = f.get(b);
            if (va == null && vb == null) {
                return 0;
            }
            if (va == null) {
                return -1;
            }
            if (vb == null) {
                return 1;
            }
            if (va instanceof Comparable ca && va.getClass().isInstance(vb)) {
                return ca.compareTo(vb);
            }
            return va.toString().compareTo(vb.toString());
        } catch (IllegalAccessException e) {
            return 0;
        }
    }

    /**
     * 归一化表达式中的列名（驼峰/去下划线变体 -> 真实列名）。
     */
    private String normalizeColumns(String expr, Class<?> entityClass) {
        if (expr == null || expr.isEmpty()) {
            return expr;
        }
        Map<String, Field> fields = fieldsOf(entityClass);
        List<String[]> rules = new ArrayList<>();
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            String canonical = e.getKey();
            addRule(rules, e.getValue().getName(), canonical);
            addRule(rules, e.getValue().getName().toLowerCase(), canonical);
            addRule(rules, canonical.replace("_", ""), canonical);
        }
        rules.sort((x, y) -> y[0].length() - x[0].length());
        String out = expr;
        for (String[] rule : rules) {
            out = out.replaceAll("(?<![\\w])" + java.util.regex.Pattern.quote(rule[0]) + "(?![\\w])",
                    java.util.regex.Matcher.quoteReplacement(rule[1]));
        }
        return out;
    }

    /**
     * 追加别名规则（去重、忽略同名词）。
     */
    private void addRule(List<String[]> rules, String variant, String canonical) {
        if (variant != null && !variant.isEmpty() && !variant.equals(canonical)
                && rules.stream().noneMatch(r -> r[0].equals(variant))) {
            rules.add(new String[]{variant, canonical});
        }
    }

    /**
     * 实体字段 -> snake_case 列名映射缓存。
     */
    private static Map<String, Field> fieldsOf(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, Field> map = new ConcurrentHashMap<>();
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    map.putIfAbsent(camelToSnake(f.getName()), f);
                }
            }
            return map;
        });
    }

    /**
     * 驼峰转 snake_case。
     */
    private static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isUpperCase(ch)) {
                sb.append('_').append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 按目标字段类型转换数据库取值。
     */
    private static Object convert(Object v, Class<?> type) {
        if (type == String.class) {
            return v.toString();
        }
        long asLong = Long.MIN_VALUE;
        double asDouble = Double.NaN;
        boolean numeric = false;
        if (v instanceof Number num) {
            asLong = num.longValue();
            asDouble = num.doubleValue();
            numeric = true;
        } else if (v instanceof Boolean b) {
            return b;
        } else if (v instanceof Instant inst) {
            asLong = inst.toEpochMilli();
            numeric = true;
        } else {
            String s = v.toString().trim();
            try {
                BigDecimal bd = new BigDecimal(s);
                asLong = bd.longValue();
                asDouble = bd.doubleValue();
                numeric = true;
            } catch (NumberFormatException nfe) {
                // 仅时间类目标字段才尝试多格式解析
                asLong = toEpochMilli(s);
                if (type != long.class && type != Long.class
                        && type != java.util.Date.class && type != java.sql.Timestamp.class) {
                    throw new IllegalArgumentException("无法将值映射到字段类型 " + type.getName() + ": " + s);
                }
                numeric = true;
            }
        }
        if (type == boolean.class || type == Boolean.class) {
            return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
        }
        if (type == int.class || type == Integer.class) {
            return (int) asLong;
        }
        if (type == long.class || type == Long.class) {
            return asLong;
        }
        if (type == double.class || type == Double.class) {
            return asDouble;
        }
        if (type == float.class || type == Float.class) {
            return (float) asDouble;
        }
        if (type == java.util.Date.class) {
            return new java.util.Date(asLong);
        }
        if (type == java.sql.Timestamp.class) {
            return new java.sql.Timestamp(asLong);
        }
        return v.toString();
    }

    /**
     * 多格式时间字符串转 epoch 毫秒。
     */
    private static long toEpochMilli(String s) {
        for (DateTimeFormatter f : new DateTimeFormatter[]{
                DateTimeFormatter.ISO_INSTANT, DateTimeFormatter.ISO_OFFSET_DATE_TIME,
                DateTimeFormatter.ISO_LOCAL_DATE_TIME}) {
            try {
                if (f == DateTimeFormatter.ISO_LOCAL_DATE_TIME) {
                    return Instant.parse(s + "Z").toEpochMilli();
                }
                return Instant.parse(s).toEpochMilli();
            } catch (Exception ignored) {
                // 尝试下一格式
            }
        }
        throw new IllegalArgumentException("无法解析时间戳: " + s);
    }

    @Override
    public void close() {
        super.close();
    }
}
