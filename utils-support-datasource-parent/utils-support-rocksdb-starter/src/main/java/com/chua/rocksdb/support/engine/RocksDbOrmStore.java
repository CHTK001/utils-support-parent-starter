package com.chua.rocksdb.support.engine;

import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.engine.MemoryWhereParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RocksDB ORM 存储辅助类，提供实体（entity）在 RocksDB 中的存取能力。
 *
 * <p>键布局：{@code ORM:<table>:<id>}，值 为 实体 JSON。
 * 表名 取 自 {@code AbstractEngine.resolveTableName}（优先 {@code @TableName}，
 * 其次 驼峰 转 下划线）。id 优先 读取 实体 的 {@code id} 属性，缺省 时
 * 按 表 分配 递增 序号（键 {@code ORM:<table>:__seq__}）。</p>
 *
 * <p>查询 走 前缀 扫描 + 反序列化，可选 {@link MemoryWhereParser} 内存 过滤
 * （WHERE 子句 由 lambda 包装器 渲染，与 内存 引擎 同 语法）。
 * 更新 / 删除 基于 同一 谓词 筛选 后 回写 / 移除 行，行级 原子 批量。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RocksDbOrmStore {

    /** ORM 键 前缀 */
    public static final String ORM_PREFIX = "ORM:";
    /** 表 级 序号 键 后缀 */
    private static final String SEQ_SUFFIX = "__seq__";
    /** 键 字段 索引 */
    private static final int KEY_FIELD = 0;
    /** 实体 字段 索引 */
    private static final int ENTITY_FIELD = 1;

    /** JSON 序列化 器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** RocksDB 实例 */
    private final RocksDB db;

    /**
     * 构造 ORM 存储。
     *
     * @param db RocksDB 实例
     */
    public RocksDbOrmStore(RocksDB db) {
        this.db = db;
    }

    /**
     * 持久化 实体 列表 到 RocksDB（按 实体 自身 id 或 自增 序号 分 键 写入）。
     *
     * @param table    表名
     * @param entities 实体 列表
     * @return 写入 行数
     */
    public <T> int store(String table, List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        WriteBatch batch = new WriteBatch();
        try {
            for (T entity : entities) {
                String key = ormKey(table, resolveId(table, entity, entity.getClass()));
                batch.put(key.getBytes(StandardCharsets.UTF_8), toEntityJson(entity));
            }
            try (WriteOptions writeOptions = new WriteOptions()) {
                db.write(writeOptions, batch);
            }
            return entities.size();
        } catch (RocksDBException e) {
            batch.close();
            throw new IllegalStateException("RocksDB ORM 存储 失败: " + table, e);
        }
    }

    /**
     * 执行 实体 查询：前缀 扫描 + 可选 WHERE 内存 过滤。
     *
     * @param where       WHERE 子句（不含 WHERE 关键字），可为 空
     * @param params      参数 列表
     * @param entityClass 实体 类 类型
     * @param limit       限制，0 表示 无 限制
     * @param offset      偏移 量，0 表示 从 头 开始
     * @param <T>         实体 类型
     * @return 查询 结果
     */
    public <T> List<T> query(String where, List<Object> params, Class<T> entityClass, int limit, int offset) {
        byte[] prefix = tablePrefix(entityClass);
        List<T> rows = scanTable(prefix, entityClass);
        if (where != null && !where.trim().isEmpty()) {
            rows = filterEntities(rows, where, params);
        }
        if (limit <= 0 && offset <= 0) {
            return rows;
        }
        int from = Math.max(offset, 0);
        if (from >= rows.size()) {
            return Collections.emptyList();
        }
        int to = limit > 0 ? Math.min(from + limit, rows.size()) : rows.size();
        return new ArrayList<>(rows.subList(from, to));
    }

    /**
     * 执行 实体 更新：按 WHERE 过滤 命中 行，应用 SET 字段 后 原子 回写。
     *
     * @param where       WHERE 子句
     * @param params      参数 列表
     * @param setValues   设置 字段 映射（字段名 为 目标 值）
     * @param entityClass 实体 类 类型
     * @param <T>         实体 类型
     * @return 影响 行数
     */
    @SuppressWarnings("unchecked")
    public <T> int update(String where, List<Object> params, Map<String, Object> setValues, Class<T> entityClass) {
        byte[] prefix = tablePrefix(entityClass);
        List<Object> matched = filterRows(prefix, where, params, entityClass);
        if (matched.isEmpty()) {
            return 0;
        }
        WriteBatch batch = new WriteBatch();
        try {
            for (Object row : matched) {
                List<Object> pair = (List<Object>) row;
                String key = (String) pair.get(KEY_FIELD);
                T entity = (T) pair.get(ENTITY_FIELD);
                for (Map.Entry<String, Object> entry : setValues.entrySet()) {
                    setFieldValue(entity, entry.getKey(), entry.getValue());
                }
                batch.put(key.getBytes(StandardCharsets.UTF_8), toEntityJson(entity));
            }
            try (WriteOptions writeOptions = new WriteOptions()) {
                db.write(writeOptions, batch);
            }
            return matched.size();
        } catch (RocksDBException e) {
            batch.close();
            throw new IllegalStateException("RocksDB ORM 更新 失败: " + RocksDbEngine.resolveTableName(entityClass), e);
        }
    }

    /**
     * 执行 实体 删除：按 WHERE 过滤 命中 行，原子 移除 对应 键。
     *
     * @param where       WHERE 子句
     * @param params      参数 列表
     * @param entityClass 实体 类 类型
     * @param <T>         实体 类型
     * @return 影响 行数
     */
    @SuppressWarnings("unchecked")
    public <T> int delete(String where, List<Object> params, Class<T> entityClass) {
        byte[] prefix = tablePrefix(entityClass);
        List<Object> matched = filterRows(prefix, where, params, entityClass);
        if (matched.isEmpty()) {
            return 0;
        }
        WriteBatch batch = new WriteBatch();
        try {
            for (Object row : matched) {
                String key = (String) ((List<Object>) row).get(KEY_FIELD);
                batch.delete(key.getBytes(StandardCharsets.UTF_8));
            }
            try (WriteOptions writeOptions = new WriteOptions()) {
                db.write(writeOptions, batch);
            }
            return matched.size();
        } catch (RocksDBException e) {
            batch.close();
            throw new IllegalStateException("RocksDB ORM 删除 失败: " + RocksDbEngine.resolveTableName(entityClass), e);
        }
    }

    /**
     * 统计 实体 表 中 WHERE 命中 行数。
     *
     * @param where       WHERE 子句
     * @param params      参数 列表
     * @param entityClass 实体 类 类型
     * @param <T>         实体 类型
     * @return 命中 行数
     */
    public <T> long count(String where, List<Object> params, Class<T> entityClass) {
        byte[] prefix = tablePrefix(entityClass);
        return filterRows(prefix, where, params, entityClass).size();
    }

    /**
     * 构造 ORM 行 键。
     *
     * @param table 表名
     * @param id    行 id
     * @return 完整 键
     */
    private static String ormKey(String table, Object id) {
        return ORM_PREFIX + table + ":" + id;
    }

    /**
     * 构造 表 前缀 键。
     *
     * @param entityClass 实体 类 类型
     * @param <T> 实体 类型
     * @return 表 前缀
     */
    private static <T> byte[] tablePrefix(Class<T> entityClass) {
        return (ORM_PREFIX + RocksDbEngine.resolveTableName(entityClass) + ":").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 表 前缀 扫描 + 实体 反 序列化。
     *
     * @param prefix      表 前缀
     * @param entityClass 实体 类 类型
     * @param <T>         实体 类型
     * @return 实体 列表（跳过 序号 键）
     */
    private <T> List<T> scanTable(byte[] prefix, Class<T> entityClass) {
        List<T> rows = new ArrayList<>();
        try (RocksIterator iter = db.newIterator()) {
            for (iter.seek(prefix); iter.isValid() && startsWith(iter.key(), prefix); iter.next()) {
                String keyStr = new String(iter.key(), StandardCharsets.UTF_8);
                if (keyStr.endsWith(":" + SEQ_SUFFIX)) {
                    continue;
                }
                T entity = fromEntityJson(new String(iter.value(), StandardCharsets.UTF_8), entityClass);
                if (entity != null) {
                    rows.add(entity);
                }
            }
        }
        return rows;
    }

    /**
     * 表 前缀 扫描 + WHERE 过滤，返回 [键字符串, 实体] 行 对 列表。
     *
     * @param prefix      表 前缀
     * @param where       WHERE 子句，可为 空
     * @param params      参数 列表
     * @param entityClass 实体 类 类型
     * @return 命中 行 列表
     */
    @SuppressWarnings("unchecked")
    private <T> List<Object> filterRows(byte[] prefix, String where, List<Object> params, Class<T> entityClass) {
        List<Object> all = scanRowPairs(prefix, entityClass);
        if (where == null || where.trim().isEmpty()) {
            return all;
        }
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> paramList = params != null ? params : Collections.emptyList();
        var predicate = parser.parse(where, paramList);
        return all.stream().filter(row -> {
            Object entity = ((List<Object>) row).get(ENTITY_FIELD);
            return entity != null && predicate.test(entity);
        }).toList();
    }

    /**
     * 表 前缀 扫描，返回 [键字符串, 实体] 行 对 列表（跳过 序号 键）。
     *
     * @param prefix      表 前缀
     * @param entityClass 实体 类 类型
     * @return 行 对 列表
     */
    @SuppressWarnings("unchecked")
    private <T> List<Object> scanRowPairs(byte[] prefix, Class<T> entityClass) {
        List<Object> rows = new ArrayList<>();
        try (RocksIterator iter = db.newIterator()) {
            for (iter.seek(prefix); iter.isValid() && startsWith(iter.key(), prefix); iter.next()) {
                String keyStr = new String(iter.key(), StandardCharsets.UTF_8);
                if (keyStr.endsWith(":" + SEQ_SUFFIX)) {
                    continue;
                }
                T entity = fromEntityJson(new String(iter.value(), StandardCharsets.UTF_8), entityClass);
                if (entity != null) {
                    List<Object> row = new ArrayList<>(2);
                    row.add(keyStr);
                    row.add(entity);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * 对 实体 列表 执行 WHERE 内存 过滤。
     *
     * @param entities    实体 列表
     * @param where       WHERE 子句
     * @param params      参数 列表
     * @param <T> 实体 类型
     * @return 过滤 后 列表
     */
    private <T> List<T> filterEntities(List<T> entities, String where, List<Object> params) {
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> paramList = params != null ? params : Collections.emptyList();
        var predicate = parser.parse(where, paramList);
        return entities.stream().filter(predicate).toList();
    }

    /**
     * 解析 实体 行 id：优先 {@code id} 属性，缺省 取 表 级 递增 序号。
     *
     * @param table 表名
     * @param entity 实体 实例
     * @param entityClass 实体 类 类型
     * @return id 字符串
     */
    private String resolveId(String table, Object entity, Class<?> entityClass) {
        Object idValue = readIdProperty(entity, entityClass);
        if (idValue != null) {
            return String.valueOf(idValue);
        }
        return String.valueOf(nextSeq(table));
    }

    /**
     * 取 表 级 递增 序号（键 级 读取 + 回写，单 连接 内 单调）。
     *
     * @param table 表名
     * @return 新 序号
     */
    private long nextSeq(String table) {
        byte[] seqKey = (ORM_PREFIX + table + ":" + SEQ_SUFFIX).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] raw = db.get(seqKey);
            long seq = raw == null ? 1L : Long.parseLong(new String(raw, StandardCharsets.UTF_8));
            db.put(seqKey, String.valueOf(seq).getBytes(StandardCharsets.UTF_8));
            return seq;
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 序号 读取 失败: " + table, e);
        }
    }

    /**
     * 读取 实体 id 属性（按 {@code id} 字段 名 反射 取值，取 不到 返回 空）。
     *
     * @param entity 实体 实例
     * @param entityClass 实体 类 类型
     * @return id 属性 值，未 命中 返回 空
     */
    private static Object readIdProperty(Object entity, Class<?> entityClass) {
        Class<?> clazz = entityClass;
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField("id");
                field.setAccessible(true);
                return field.get(entity);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 实体 序列 化 为 JSON 字节。
     *
     * @param entity 实体 实例
     * @return JSON 字节
     */
    private static byte[] toEntityJson(Object entity) {
        try {
            return MAPPER.writeValueAsBytes(entity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("RocksDB ORM 实体 序列 化 失败: " + entity.getClass().getSimpleName(), e);
        }
    }

    /**
     * JSON 反 序列化 为 实体。
     *
     * @param json JSON 字符串
     * @param entityClass 实体 类 类型
     * @param <T> 实体 类型
     * @return 实体 实例，反 序列化 失败 返回 空
     */
    private static <T> T fromEntityJson(String json, Class<T> entityClass) {
        try {
            return MAPPER.readValue(json, entityClass);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 反射 设置 实体 字段 值（含 父 类 查找 与 类型 适配）。
     *
     * @param entity 实体 实例
     * @param field 字段名
     * @param value 字段 值
     */
    private static void setFieldValue(Object entity, String field, Object value) {
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField(field);
                f.setAccessible(true);
                f.set(entity, coerceValue(f.getType(), value));
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }

    /**
     * 值 类型 适配：字符串 数字 互 转，保证 数字 字面 量 写 入 数字 字段 不 抛 类型 异常。
     *
     * @param targetType 目标 类型
     * @param value 原始 值
     * @return 适配 后 的 值
     */
    private static Object coerceValue(Class<?> targetType, Object value) {
        if (value == null || targetType.isInstance(value) || targetType == Object.class) {
            return value;
        }
        if (value instanceof String s) {
            String str = s.trim();
            if (str.isEmpty()) {
                return null;
            }
            if (targetType == int.class || targetType == Integer.class) {
                return Integer.valueOf(str);
            }
            if (targetType == long.class || targetType == Long.class) {
                return Long.valueOf(str);
            }
            if (targetType == double.class || targetType == Double.class) {
                return Double.valueOf(str);
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return Boolean.parseBoolean(str);
            }
            return value;
        }
        if (value instanceof Number num && targetType == String.class) {
            return String.valueOf(num);
        }
        return value;
    }

    /**
     * 解析实体类的表名（与 {@code EngineQueryWrapper} 等 Lambda 链路的 {@link LambdaUtils#resolveColumn}
     * 列 解析 规则 对齐：优先 {@link TableName} 注解 值，其次 实体 类 简单 名 小写 直用——
     * 与 基类 {@code resolveTableName} 的 驼峰 转 下划线 不同，这里 保留 原始 简单 名
     * 以 保证 {@code store(name, data)} 中 使用 类 简单 名 的 表名 与 查询 前缀 一致）。
     *
     * @param entityClass 实体 类 类型
     * @param <T> 实体 类型
     * @return 表名
     */
    public static <T> String resolveEntityTableName(Class<T> entityClass) {
        TableName annotation = entityClass.getAnnotation(TableName.class);
        if (annotation != null && !annotation.value().isEmpty()) {
            return annotation.value();
        }
        return entityClass.getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 判断 字节 数组 是否 以 指定 前缀 开头。
     *
     * @param data 数据
     * @param prefix 前缀
     * @return true 表示 匹配
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
}
