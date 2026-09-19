package com.chua.rocksdb.support.engine;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.datasource.support.annotation.TableName;
import com.chua.datasource.support.engine.MemoryWhereParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocksDB ORM 存储辅助类，提供实体（entity）在 RocksDB 中的存取能力。
 *
 * <p>键布局：{@code ORM:<table>:<id>}，值 为 实体 JSON。
 * 表名 优先 取 {@link TableName} 注解 值，未 标注 时 取 实体 类 简单 名 小写
 * （与 {@code EngineQueryWrapper} 的 列 解析 同 规则 基准）。
 * id 优先 读取 实体 的 {@code id} 属性，缺省 时 按 表 分配 递增 序号
 * （键 {@code ORM:<table>:__seq__}，表 级 锁 保证 分配 原子 单调，
 * 避免 并发 分配 到 相同 序号 导致 行 键 冲突）。</p>
 *
 * <p>查询 走 前缀 扫描 + 反 序列化，WHERE 过滤 时 把 lambda 渲染 的
 * 下划线 列 名（如 {@code dept_id}）映射 回 实体 驼峰 字段（{@code deptId}），
 * 再 走 反射 取 值 参与 谓词 判断（{@link #entityPredicate}），避免
 * snake 列 名 在 反射 getter 解析 中 失配。
 * 更新 / 删除 基于 同一 谓词 筛选 后 回写 / 移除 行，行 级 原子 批量；
 * 表 级 锁 串行化 同 表 的 读-改-写 循环，消除 并 发 更新 与 并 发 删除 的
 * 竞态（H3）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
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

    /**
    * 实体 字段 反射 缓存：类 → 字段名 → {@link Field}（已 setAccessible）
    */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 表 级 锁：表名 → {@link Object}，串行化 同 表 的 序号 分配 与 读-改-写 循环
     */
    private final Map<String, Object> tableLocks;

    /** RocksDB 实例 */
    private final RocksDB db;

    /**
    * 构造 ORM 存储。
    *
    * @param db RocksDB 实例
    */
    public RocksDbOrmStore(RocksDB db) {
        this(db, new ConcurrentHashMap<>());
    }

    /**
     * 构造 ORM 存储（显式 传入 表 级 锁 映射，供 引擎 共享 锁 句柄 以 保证 同 表 串行）。
     *
     * @param db         RocksDB 实例
     * @param tableLocks 表 级 锁 映射（表名 → 锁 句柄），必须 是 线程 安全 的
     */
    public RocksDbOrmStore(RocksDB db, Map<String, Object> tableLocks) {
        this.db = db;
        this.tableLocks = tableLocks;
    }

    /**
     * 获取 表 级 锁 句柄（同 表 共享 同一 锁 实例，跨 表 互不 影响）。
     *
     * @param table 表名
     * @return 锁 句柄
     */
    private Object tableLock(String table) {
        return tableLocks.computeIfAbsent(table, k -> new Object());
    }

    /**
     * 解析 实体 类 的 ORM 表名。
     * <p>优先 {@link TableName} 注解 值；未 标注 时 取 实体 类 简单 名 小写
     * （不 做 驼峰 转 下划线，保证 {@code store(name, data)} 使用 类 简单 名
     * 与 查询 前缀 一致）。</p>
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
        return entityClass.getSimpleName().toLowerCase(Locale.ROOT);
    }

    /**
     * 持久化 实体 列表 到 RocksDB（按 实体 自身 id 或 自增 序号 分 键 写入）。
     * <p>序号 分配 在 表 级 锁 内 完成，保证 并 发 写入 不 产生 重复 行 键。</p>
     *
     * @param table    表名
     * @param entities 实体 列表
     * @return 写入 行数
     */
    public <T> int store(String table, List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        synchronized (tableLock(table)) {
            return storeLocked(table, entities);
        }
    }

    /**
     * 表 级 锁 内 的 存储 实现（调用 方 必须 持有 {@link #tableLock}）。
     *
     * @param table    表名
     * @param entities 实体 列表
     * @return 写入 行数
     */
    @SuppressWarnings("unchecked")
    private <T> int storeLocked(String table, List<T> entities) {
        WriteBatch batch = new WriteBatch();
        try {
            for (T entity : entities) {
                String key = ormKey(table, resolveIdLocked(table, entity, entity.getClass()));
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
     * 按 实体 类 自身 表名 持久化 实体 列表。
     *
     * @param entities    实体 列表
     * @param entityClass 实体 类 类型
     * @param <T>         实体 类型
     * @return 写入 行数
     */
    public <T> int store(List<T> entities, Class<T> entityClass) {
        return store(resolveEntityTableName(entityClass), entities);
    }

    /**
     * 执行 实体 查询：前缀 扫描 + 可选 WHERE 内存 过滤（列 名 已 映射 回 实体 字段）。
     * <p>limit/offset 由 基类 {@code processQueryResult} 统一 截取，本 方法
     * 仅 负责 谓词 过滤；无 WHERE 时 仍 需 全 量 扫描（谓词 未 下推，无法
     * 靠 RocksDB 键 范围 早 停——行 键 为 自增 序号 / 实体 id，与 逻辑 分页
     * 无 序 关系）。</p>
     *
     * @param where       WHERE 子句（不含 WHERE 关键字），可为 空
     * @param params      参数 列表
     * @param entityClass 实体 类 类型
     * @param limit       限制（仅 供 语义 参考，实际 截取 由 基类 完成）
     * @param offset      偏移 量（仅 供 语义 参考，实际 截取 由 基类 完成）
     * @param <T>         实体 类型
     * @return 查询 结果
     */
    public <T> List<T> query(String where, List<Object> params, Class<T> entityClass, int limit, int offset) {
        byte[] prefix = tablePrefix(entityClass);
        List<T> rows = scanTable(prefix, entityClass);
        if (where == null || where.trim().isEmpty()) {
            return rows;
        }
        Map<String, String> fieldMap = fieldMap(entityClass);
        return rows.stream().filter(e -> entityPredicate(e, fieldMap, where, params)).toList();
    }

    /**
     * 执行 实体 更新：按 WHERE 过滤 命中 行，SET 列 名 映射 回 实体 字段 后
     * 反射 写 入，原子 回写 RocksDB。
     * <p>整个 读-改-写 循环 在 表 级 锁 内 完成，避免 并 发 删除 在 扫描 后
     * 写 入 前 介入 导致 已 删除 行 被 复活（H3 修复）。</p>
     *
     * @param where       WHERE 子句
     * @param params      参数 列表
     * @param setValues   设置 字段 映射（下划线 列 名 为 目标 值）
     * @param entityClass 实体 类 类型
     * @param <T>         实体 类型
     * @return 影响 行数
     */
    @SuppressWarnings("unchecked")
    public <T> int update(String where, List<Object> params, Map<String, Object> setValues, Class<T> entityClass) {
        String table = resolveEntityTableName(entityClass);
        byte[] prefix = tablePrefix(entityClass);
        Map<String, String> fieldMap = fieldMap(entityClass);
        synchronized (tableLock(table)) {
            List<Object> matched = filterRows(prefix, where, params, entityClass, fieldMap);
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
                        String field = fieldMap.getOrDefault(entry.getKey(), entry.getKey());
                        setFieldValue(entity, field, entry.getValue());
                    }
                    batch.put(key.getBytes(StandardCharsets.UTF_8), toEntityJson(entity));
                }
                try (WriteOptions writeOptions = new WriteOptions()) {
                    db.write(writeOptions, batch);
                }
                return matched.size();
            } catch (RocksDBException e) {
                batch.close();
                throw new IllegalStateException("RocksDB ORM 更新 失败: " + table, e);
            }
        }
    }

    /**
     * 执行 实体 删除：按 WHERE 过滤 命中 行，原子 移除 对应 键。
     * <p>整个 读-删 循环 在 表 级 锁 内 完成，避免 并 发 更新 在 删除 后
     * 写 回 已 删除 行（H3 修复）。</p>
     *
     * @param where       WHERE 子句
     * @param params      参数 列表
     * @param entityClass 实体 类 类型
     * @param <T>         实体 类型
     * @return 影响 行数
     */
    @SuppressWarnings("unchecked")
    public <T> int delete(String where, List<Object> params, Class<T> entityClass) {
        String table = resolveEntityTableName(entityClass);
        byte[] prefix = tablePrefix(entityClass);
        Map<String, String> fieldMap = fieldMap(entityClass);
        synchronized (tableLock(table)) {
            List<Object> matched = filterRows(prefix, where, params, entityClass, fieldMap);
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
                throw new IllegalStateException("RocksDB ORM 删除 失败: " + table, e);
            }
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
        return filterRows(prefix, where, params, entityClass, fieldMap(entityClass)).size();
    }

    // ==================== 谓词 构建（列 名 → 字段 名 映射 后 反射 取 值） ====================

    /**
     * 将 lambda 渲染 的 下划线 列 名 映射 回 实体 驼峰 字段（{@code dept_id → deptId}）。
     * <p>WHERE/SET 子句 中 出现 的 列 名 先 查 映射；未 命中 时 原样 保留，
     * 由 后续 反射 取 值 兜底 处理 单 词 字段（如 {@code id}、{@code name}）。</p>
     *
     * @param where WHERE 子句（可为 空）
     * @param params 参数 列表
     * @return 列 名 已 映射 为 字段 名 的 WHERE 子句
     * @param fieldMap 字段映射，不允许为 null
     */
    private static String mapWhereColumns(String where, Map<String, String> fieldMap) {
        if (where == null || where.trim().isEmpty() || fieldMap.isEmpty()) {
            return where;
        }
        Set<String> columns = extractColumns(where);
        if (columns.isEmpty()) {
            return where;
        }
        String mapped = where;
        for (String column : columns) {
            String field = fieldMap.get(column);
            if (field != null && !field.equals(column)) {
                mapped = mapped.replaceAll("(?<![\\w])" + java.util.regex.Pattern.quote(column) + "(?![\\w])", field);
            }
        }
        return mapped;
    }

    /**
     * 从 WHERE 子句 提取 列 名（二元 比较 条件 的 左 操作 数 与 等值 字段）。
     *
     * @param where WHERE 子句
     * @return 列 名 集合（保持 出现 顺序）
     */
    private static Set<String> extractColumns(String where) {
        Set<String> columns = new LinkedHashSet<>();
        for (String token : where.split("[\\s()=<>!]+")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (isSqlKeyword(t)) {
                continue;
            }
            if (Character.isLetter(t.charAt(0)) && t.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                columns.add(t);
            }
        }
        return columns;
    }

    /**
     * 判断 是否 SQL 关键字（WHERE 渲染 中 的 非 列 标识 符）。
     *
     * @param token 标识 符
     * @return true 表示 应 跳过
     */
    private static boolean isSqlKeyword(String token) {
        switch (token.toUpperCase(Locale.ROOT)) {
            case "AND":
            case "OR":
            case "NOT":
            case "IN":
            case "BETWEEN":
            case "LIKE":
            case "IS":
            case "NULL":
            case "TRUE":
            case "FALSE":
                return true;
            default:
                return false;
        }
    }

    /**
     * 实体 谓词：把 WHERE 列 名 映射 回 字段 后，反射 取 值 参与
     * {@link MemoryWhereParser} 解析 的 条件 判断。
     *
     * @param entity  实体 实例
     * @param fieldMap 列 名 → 字段 名 映射
     * @param where   WHERE 子句
     * @param params  参数 列表
     * @return 是否 命中
     */
    private static boolean entityPredicate(Object entity, Map<String, String> fieldMap, String where, List<Object> params) {
        String mapped = mapWhereColumns(where, fieldMap);
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> paramList = params != null ? params : Collections.emptyList();
        var predicate = parser.parse(mapped, paramList);
        return predicate.test(entity);
    }

    // ==================== 实体 字段 反射（Field 级，snake ↔ camel） ====================

    /**
     * 构建 实体 类 的 下划线 列 名 → 驼峰 字段 名 映射（含 父 类 字段）。
     *
     * @param entityClass 实体 类 类型
     * @return 列 名 → 字段 名 映射
     */
    private static Map<String, String> fieldMap(Class<?> entityClass) {
        Map<String, String> map = new HashMap<>();
        Class<?> clazz = entityClass;
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                map.put(toSnakeCase(f.getName()), f.getName());
                map.put(f.getName(), f.getName());
            }
            clazz = clazz.getSuperclass();
        }
        return map;
    }

    /**
     * 取 实体 类 的 字段 反射 句柄（含 父 类 查找，已 {@code setAccessible}，带 缓存）。
     *
     * @param entityClass 实体 类 类型
     * @param field       字段名（驼峰）
     * @return 字段 句柄，未 找到 返回 空
     */
    private static Field findField(Class<?> entityClass, String field) {
        Map<String, Field> cache = FIELD_CACHE.computeIfAbsent(entityClass, k -> new ConcurrentHashMap<>());
        return cache.computeIfAbsent(field, f -> {
            Field fd = ReflectUtils.findField(entityClass, f);
            if (fd == null) {
                return null;
            }
            fd.setAccessible(true);
            return fd;
        });
    }

    /**
     * 驼峰 转 下划线（{@code deptId → dept_id}）。
     *
     * @param name 驼峰 名
     * @return 下划线 名
     */
    private static String toSnakeCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 反射 设置 实体 字段 值（含 父 类 查找 与 类型 适配）。
     *
     * @param entity 实体 实例
     * @param field  字段名（驼峰）
     * @param value  字段 值
     */
    private static void setFieldValue(Object entity, String field, Object value) {
        Field f = findField(entity.getClass(), field);
        if (f == null) {
            log.warn("RocksDB ORM 更新 未 找到 实体 字段: {}.{}", entity.getClass().getSimpleName(), field);
            return;
        }
        try {
            f.set(entity, coerceValue(f.getType(), value));
        } catch (Exception e) {
            log.warn("RocksDB ORM 更新 字段 写 入 失败: {}.{} = {}",
                    entity.getClass().getSimpleName(), field, value, e);
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

    // ==================== 键 布局 与 扫描 ====================

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
        return (ORM_PREFIX + resolveEntityTableName(entityClass) + ":").getBytes(StandardCharsets.UTF_8);
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
     * 表 前缀 扫描 + WHERE 过滤（列 名 映射 回 字段），返回 [键字符串, 实体] 行 对 列表。
     *
     * @param prefix   表 前缀
     * @param where    WHERE 子句，可为 空
     * @param params   参数 列表
     * @param entityClass 实体 类 类型
     * @param fieldMap 列 名 → 字段 名 映射
     * @return 命中 行 列表
     */
    @SuppressWarnings("unchecked")
    private <T> List<Object> filterRows(byte[] prefix, String where, List<Object> params,
                                       Class<T> entityClass, Map<String, String> fieldMap) {
        List<Object> all = scanRowPairs(prefix, entityClass);
        if (where == null || where.trim().isEmpty()) {
            return all;
        }
        return all.stream().filter(row -> {
            Object entity = ((List<Object>) row).get(ENTITY_FIELD);
            return entity != null && entityPredicate(entity, fieldMap, where, params);
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
     * 解析 实体 行 id：优先 {@code id} 属性，缺省 取 表 级 递增 序号（8 位 零 填充）。
     * <p>调用 方 必须 已 持有 表 级 锁（{@link #tableLock}），序号 分配 才 原子。</p>
     *
     * @param table 表名
     * @param entity 实体 实例
     * @param entityClass 实体 类 类型
     * @return id 字符串
     */
    private String resolveIdLocked(String table, Object entity, Class<?> entityClass) {
        Object idValue = readIdProperty(entity, entityClass);
        if (idValue != null) {
            return String.valueOf(idValue);
        }
        return seqKeySuffix(nextSeqLocked(table));
    }

    /**
     * 取 表 级 递增 序号（键 级 读取 + 回写，调用 方 必须 持有 表 级 锁 才 原子）。
     * <p>计数 器 存储 为 十 进制 数字 符 串（"下一 可用 值"），读 出 后 取 当前 值、
     * 回写 当前 值 +1。序号 以 8 位 零 填充 十 进制 编 码 作 为 行 键 后缀
     * （字典 序 = 数值 序，避免 "10" 排 在 "2" 前 的 字典 序 陷阱），
     * 行 键 扫描 顺序 即 插入 顺序。</p>
     *
     * @param table 表名
     * @return 新 序号
     */
    private long nextSeqLocked(String table) {
        byte[] seqKey = (ORM_PREFIX + table + ":" + SEQ_SUFFIX).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] raw = db.get(seqKey);
            long next = raw == null ? 1L : Long.parseLong(new String(raw, StandardCharsets.UTF_8));
            db.put(seqKey, String.valueOf(next + 1).getBytes(StandardCharsets.UTF_8));
            return next;
        } catch (RocksDBException e) {
            throw new IllegalStateException("RocksDB 序号 读取 失败: " + table, e);
        }
    }

    /**
     * 将 自增 序号 编码 为 8 位 零 填充 行 键 后缀（字典 序 = 数值 序）。
     *
     * @param seq 序号
     * @return 8 位 零 填充 十 进制 字符 串
     */
    private static String seqKeySuffix(long seq) {
        return String.format("%08d", seq);
    }

    /**
     * 读取 实体 id 属性（按 {@code id} 字段 名 反射 取值，取 不到 返回 空）。
     *
     * @param entity 实体 实例
     * @param entityClass 实体 类 类型
     * @return id 属性 值，未 命中 返回 空
     */
    private static Object readIdProperty(Object entity, Class<?> entityClass) {
        Field field = findField(entityClass, "id");
        if (field == null) {
            return null;
        }
        try {
            return field.get(entity);
        } catch (Exception e) {
            return null;
        }
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
     * <p>反 序列化 失败 时 记录 告警 日志（含 异常 信息），返回 空 让 调用 方
     * 跳 过 该 行，避免 静默 丢 数据（M2 修复）。</p>
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
            log.warn("RocksDB ORM 实体 反 序列化 失败，跳 过 该 行: {}", entityClass.getSimpleName(), e);
            return null;
        }
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
