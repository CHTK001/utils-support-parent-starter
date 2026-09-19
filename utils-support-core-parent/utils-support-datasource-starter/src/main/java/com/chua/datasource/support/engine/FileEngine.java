package com.chua.datasource.support.engine;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.FileUtils;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件引擎实现，支持 CSV、Excel、JSON、DBF 等表格文件的 Lambda ORM 查询。
 * <p>
 * 通过 {@link #load(String, String)} 加载文件数据到内存，利用 {@link MemoryWhereParser}
 * 解析 WHERE 条件进行内存过滤，完整支持 lambda查询包装器 / lambda更新包装器 / lambda删除包装器。
 * </p>
 * <p>
 * 支持自动持久化：更新 / 删除 操作默认写回源文件，可通过 {@link #autoPersist(boolean)} 全局关闭，
 * 或通过 {@link #autoPersist(String, boolean)} 按表关闭。也可手动调用 {@link #save(String)} / {@link #saveAll()}。
 * </p>
 *
 * <pre>{@code
 * FileEngine engine = new FileEngine();
 * engine.load("user", "user.csv")
 *       .autoPersist(true);          // 默认开启，可省略
 *
 * // 查询
 * List<User> list = engine.query(User.class)
 *     .eq(User::getName, "张三")
 *     .list();
 *
 * // 更新（自动写回 user.csv）
 * engine.update(User.class)
 *     .set(User::getAge, 25)
 *     .eq(User::getName, "张三")
 *     .update();
 *
 * // 删除（自动写回 user.csv）
 * engine.delete(User.class)
 *     .eq(User::getId, 1L)
 *     .remove();
 * }</pre>gine.delete(User.class)
 *     .eq(User::getId, 1L)
 *     .remove();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("file")
public class FileEngine extends AbstractEngine {

    /**
     * 表格文件白名单（仅支持可映射为表的格式）。
     * <ul>
     *   <li>csv / tsv → csv</li>
     *   <li>xls / xlsx → excel</li>
     *   <li>json / json5 → json</li>
     *   <li>dbf → dbf</li>
     * </ul>
     */
    public static final Set<String> SUPPORTED_TYPES = Set.of(
            "csv", "tsv", "excel", "xls", "xlsx", "json", "json5", "dbf"
    );

    /**
     * 文件表元数据
     * @author CH
     * @since 4.0.0
     */
    private static class TableMeta {
        File file; // 文件
        String fileType; // 文件类型
        boolean autoPersist; // autopersist

        TableMeta(File file, String fileType, boolean autoPersist) {
            this.file = file;
            this.fileType = fileType;
            this.autoPersist = autoPersist;
        }
    }

    /**
     * 文件表元数据映射
     */
    private final Map<String, TableMeta> tableMetas = new ConcurrentHashMap<>();

    /**
     * 默认自动持久化开关
     */
    private boolean defaultAutoPersist = true;

    // ==================== 加载 ====================

    /**
     * 按文件路径自动识别类型并加载到默认数据源。
     *
     * @param filePath 文件路径
     * @param <T>      实体类型
     * @return 当前引擎实例
     */
    public <T> FileEngine load(String filePath) {
        return load("default", new File(filePath), null);
    }

    /**
     * 加载文件到指定名称的数据源，文件类型自动识别。
     *
     * @param name     数据源名称
     * @param filePath 文件路径
     * @param <T>      实体类型
     * @return 当前引擎实例
     */
    public <T> FileEngine load(String name, String filePath) {
        return load(name, new File(filePath), null);
    }

    /**
     * 按指定类型加载文件。
     *
     * @param name     数据源名称
     * @param filePath 文件路径
     * @param type     文件类型（csv/excel/json/dbf），空 则自动识别
     * @param <T>      实体类型
     * @return 当前引擎实例
     */
    public <T> FileEngine load(String name, String filePath, String type) {
        return load(name, new File(filePath), type);
    }

    /**
     * 加载文件到指定名称的数据源。
     *
     * @param name   数据源名称
     * @param file   文件对象
     * @param type   文件类型（csv/excel/json/dbf），空 则自动识别
     * @param <T>    实体类型
     * @return 当前引擎实例
     */
    @SuppressWarnings("unchecked")
    public <T> FileEngine load(String name, File file, String type) {
        String resolved = resolveSupportedType(file, type);

        FileSystem fs = FileSystem.create(resolved);
        ReadBuilder reader = fs.read(file);
        Object result = reader.read();
        if (!(result instanceof List)) {
            throw new UnsupportedOperationException("文件格式不支持表格读取: " + file.getName());
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result;
        rows = normalizeEmptyStrings(rows);

        TableMeta meta = new TableMeta(file, resolved, defaultAutoPersist);
        tableMetas.put(name, meta);

        dataStores.put(name, new ArrayList<>(rows));
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        return this;
    }

    /**
     * 解析并校验表格文件类型，仅允许 {@link #SUPPORTED_TYPES}。
     *
     * @param file 文件
     * @param type 显式类型，可为 空（按扩展名识别）
     * @return SPI 类型名：csv / excel / json / dbf
     */
    static String resolveSupportedType(File file, String type) {
        String raw = (type == null || type.isBlank())
                ? FileUtils.getExtension(file)
                : type.trim().toLowerCase(Locale.ROOT);
        if (raw == null || raw.isBlank()) {
            throw new UnsupportedOperationException(
                    "无法识别文件类型，请显式指定 type（csv/excel/json/dbf）: " + file.getName());
        }
        raw = raw.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(raw)) {
            throw new UnsupportedOperationException(
                    "FileEngine 仅支持表格文件: csv/tsv/excel/xls/xlsx/json/json5/dbf，实际: "
                            + raw + " (" + file.getName() + ")");
        }
        return switch (raw) {
            case "tsv" -> "csv";
            case "xls", "xlsx" -> "excel";
            case "json5" -> "json";
            default -> raw;
        };
    }

    // ==================== 持久化配置 ====================

    /**
     * 全局开关：是否开启自动持久化，默认 true。
     * <p>
     * 开启后，更新 / 删除 操作会自动将变更写回源文件。
     * </p>
     *
     * @param autoPersist 是否自动持久化
     * @return 当前引擎实例
     */
    public FileEngine autoPersist(boolean autoPersist) {
        this.defaultAutoPersist = autoPersist;
        return this;
    }

    /**
     * 为指定表设置自动持久化开关。
     *
     * @param name        表名
     * @param autoPersist 是否自动持久化
     * @return 当前引擎实例
     */
    public FileEngine autoPersist(String name, boolean autoPersist) {
        TableMeta meta = tableMetas.get(name);
        if (meta != null) {
            meta.autoPersist = autoPersist;
        }
        return this;
    }

    /**
     * 手动将指定表数据写回文件。
     *
     * @param name 表名
     */
    @SuppressWarnings("unchecked")
    public void save(String name) {
        TableMeta meta = resolveMeta(name);
        if (meta == null) {
            return;
        }

        List<?> raw = resolveDataStore(name);
        if (raw == null || raw.isEmpty()) {
            writeFile(meta, Collections.emptyList());
            return;
        }

        List<Map<String, Object>> maps;
        if (raw.getFirst() instanceof Map) {
            maps = (List<Map<String, Object>>) raw;
        } else {
            maps = entitiesToMaps((List<Object>) raw);
        }
        writeFile(meta, maps);
    }

    /**
     * 解析表元数据：优先按名称，其次默认数据源，最后任意首个。
     * @param name 名称
     * @return resolveMeta的结果
     */
    private TableMeta resolveMeta(String name) {
        TableMeta meta = tableMetas.get(name);
        if (meta == null && defaultDataSourceName != null) {
            meta = tableMetas.get(defaultDataSourceName);
        }
        if (meta == null && !tableMetas.isEmpty()) {
            meta = tableMetas.values().iterator().next();
        }
        return meta;
    }

    /**
     * 解析内存数据：优先实体表名，其次 加载 时的 名称/默认。
     * <p>
     * 惰性 映射→实体 转换后数据可能挂在实体表名下，而 meta 仍用 加载 名称。
     * </p>
     * @param name 名称
     * @return resolve数据存储的结果
     */
    private List<?> resolveDataStore(String name) {
        List<?> raw = dataStores.get(name);
        if (raw == null && defaultDataSourceName != null) {
            raw = dataStores.get(defaultDataSourceName);
        }
        if (raw == null) {
            raw = dataStores.get("default");
        }
        if (raw == null && !dataStores.isEmpty()) {
 // 优先取实体列表（非 映射 行）
            for (List<?> list : dataStores.values()) {
                if (list != null && !list.isEmpty() && !(list.getFirst() instanceof Map)) {
                    return list;
                }
            }
            raw = dataStores.values().iterator().next();
        }
        return raw;
    }

    /**
     * 手动将所有表数据写回文件。
     */
    public void saveAll() {
        for (String name : tableMetas.keySet()) {
            save(name);
        }
    }

    // ==================== 查询 ====================

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 执行新查询
     *
     * @param where where
     * @param args 参数
     * @param clazz clazz
     * @param limit 限制
     * @param offset 偏移量
     * @return 执行新查询的结果
     */
    protected <T> List<T> executeNewQuery(String where, Object[] args, Class<T> clazz, int limit, int offset) {
        List<T> data = getData(clazz);
        if (data.isEmpty()) {
            return data;
        }
        if (where == null || where.trim().isEmpty()) {
            return data;
        }
        MemoryWhereParser parser = new MemoryWhereParser();
        List<Object> paramList = (args != null)
                ? Arrays.asList(args)
                : Collections.emptyList();
        var predicate = parser.parse(where, paramList);
        List<T> filtered = data.stream().filter(predicate).toList();
        return limit <= 0 && offset <= 0 ? filtered : limitSlice(filtered, limit, offset);
    }

    /**
     * 获取指定实体类对应的数据列表，并在首次访问时将 列表&lt;映射&gt; 惰性转换为 列表&lt;T&gt;。
     * <p>
     * 转换后 数据存储 中存储实体对象，后续 更新 / 删除 的 入-内存 操作可直接使用 getter/setter。
     * </p>
     * @param entityClass 实体类
     * @return 获取数据的结果
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> List<T> getData(Class<T> entityClass) {
        String tableName = getTableName(entityClass);
        String storeKey = tableName;
        List<?> data = dataStores.get(tableName);
        if (data == null && defaultDataSourceName != null) {
            storeKey = defaultDataSourceName;
            data = dataStores.get(defaultDataSourceName);
        }
        if (data == null) {
            storeKey = "default";
            data = dataStores.get("default");
        }
        if (data == null) {
            for (Map.Entry<String, List<?>> e : dataStores.entrySet()) {
                storeKey = e.getKey();
                data = e.getValue();
                break;
            }
        }
        if (data == null) {
            return Collections.emptyList();
        }

        if (!data.isEmpty() && data.getFirst() instanceof Map) {
            List<T> entities = new ArrayList<>(data.size());
            for (Object item : data) {
                entities.add(mapToEntity((Map<String, Object>) item, entityClass));
            }
 // 统一挂到实体表名 + 原 加载 名，避免 保存/更新 找不到最新数据
            dataStores.put(tableName, entities);
            dataStores.put(storeKey, entities);
            if (defaultDataSourceName != null) {
                dataStores.put(defaultDataSourceName, entities);
            }
            return entities;
        }
        return (List<T>) data;
    }

    // ==================== 更新 / 删除（自动持久化） ====================

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 执行更新
     *
     * @param sql SQL
     * @return 执行更新的结果
     */
    public <T> int executeUpdate(UpdateSql<T> sql) {
        int rows = super.executeUpdate(sql);
        if (rows > 0) {
            persistIfAuto(sql.entityClass());
        }
        return rows;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 执行删除
     *
     * @param sql SQL
     * @return 执行删除的结果
     */
    public <T> int executeDelete(DeleteSql<T> sql) {
        int rows = super.executeDelete(sql);
        if (rows > 0) {
            persistIfAuto(sql.entityClass());
        }
        return rows;
    }

    // ==================== 原生 SQL（AST → 行引用 + 自动持久化） ====================

    /**
     * 执行原生 选择：SQL 编译为 AST 后在表行引用上求值。
     *
     * @param sql    选择 语句（支持 WHERE/订单 BY/限制/数量(*)）
     * @param params ? 绑定参数
     * @return 结果行
     */
    public List<Map<String, Object>> querySql(String sql, Object... params) {
        Objects.requireNonNull(sql, "sql must not be null");
        String table = extractTable(sql);
        List<?> rows = dataStores.getOrDefault(table, Collections.emptyList());
        return new MemorySqlParser().executeQuery(sql, rows, params);
    }

    /**
     * 执行原生 DML：插入 / 更新 / 删除 作用于表行引用，
     * 影响行数大于零时按 autopersist 配置自动写回源文件。
     *
     * @param sql    DML 语句
     * @param params ? 绑定参数
     * @return 影响行数
     */
    public int executeSql(String sql, Object... params) {
        Objects.requireNonNull(sql, "sql must not be null");
        var plan = new MemorySqlParser().parseDml(sql);
        List<Object> plist = Arrays.asList(params == null ? new Object[0] : params);
        List<Object> rows = resolveMutableRows(plan.table());
        int affected = MemorySqlAst.executeDml(plan, plist, () -> rows);
        if (affected > 0) {
            TableMeta meta = resolveMeta(plan.table());
            if (meta != null && meta.autoPersist) {
                save(plan.table());
            }
        }
        return affected;
    }

    /**
     * 解析目标表的可变行引用，未加载的表直接拒绝。
     *
     * @param table 表名
     * @return 可变行引用列表
     * @throws IllegalStateException 表未通过 加载 加载时抛出
     */
    private List<Object> resolveMutableRows(String table) {
        List<?> rows = dataStores.get(table);
        if (rows == null && defaultDataSourceName != null && table.equals(defaultDataSourceName)) {
            rows = dataStores.get("default");
        }
        if (rows == null) {
            throw new IllegalStateException("表未加载，请先 load: " + table);
        }
        @SuppressWarnings("unchecked")
        List<Object> mutable = (List<Object>) rows;
        return mutable;
    }

    /**
     * 提取 从 子句后的表名，供原生 SQL 定位数据。
     *
     * @param sql 选择 或 DML 语句
     * @return 表名
     * @throws IllegalArgumentException 缺少 从 子句时抛出
     */
    private static String extractTable(String sql) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)FROM\\s+([\\w]+)").matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("缺少 FROM 子句: " + sql);
        }
        return m.group(1);
    }

    // ==================== 内部方法 ====================

    /**
     * normalize空字符串
     *
     * @param rows rows
     * @return normalize空字符串的结果
     */
    private static List<Map<String, Object>> normalizeEmptyStrings(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                map.put(entry.getKey(), value instanceof String s && s.isBlank() ? null : value);
            }
            result.add(map);
        }
        return result;
    }

    /**
     * persistifauto
     *
     * @param clazz clazz
     * @return persistIfAuto的结果
     */
    private <T> void persistIfAuto(Class<T> clazz) {
        String tableName = getTableName(clazz);
        TableMeta meta = resolveMeta(tableName);
        if (meta != null && meta.autoPersist) {
 // 保存 内部会 resolvemeta + resolve数据存储，兼容 加载 名与实体表名不一致
            save(tableName);
        }
    }

    /**
     * 写入文件
     *
     * @param meta meta
     * @param maps 映射
     * @return 写入文件的结果
     */
    private <T> void writeFile(TableMeta meta, List<Map<String, Object>> maps) {
        FileSystem fs = FileSystem.create(meta.fileType);
        WriteBuilder writer = fs.write(meta.file);
        writer.write(maps);
        writer.finish();
    }

        /**
         * 映射转为实体
         *
         * @param map 映射
         * @param clazz clazz
         * @return 映射转为实体的结果
         */
@SuppressWarnings("unchecked")
    private <T> T mapToEntity(Map<String, Object> map, Class<T> clazz) {
        try {
            T instance = ReflectUtils.instantiate(clazz);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                setFieldValue(instance, entry.getKey(), entry.getValue());
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Map 转实体失败: " + clazz.getSimpleName(), e);
        }
    }

    /**
     * 实体转为映射
     *
     * @param entities 实体
     * @return 实体转为映射的结果
     */
    private <T> List<Map<String, Object>> entitiesToMaps(List<T> entities) {
        if (entities.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> maps = new ArrayList<>(entities.size());
        for (T entity : entities) {
            Map<String, Object> map = new LinkedHashMap<>();
            collectFields(entity, entity.getClass(), map);
            maps.add(map);
        }
        return maps;
    }

    /**
     * collect字段
     *
     * @param bean Bean
     * @param clazz clazz
     * @param map 映射
     */
    private static void collectFields(Object bean, Class<?> clazz, Map<String, Object> map) {
        for (Field field : clazz.getDeclaredFields()) {
            Object value = ReflectUtils.getField(bean, field.getName());
            if (!map.containsKey(field.getName())) {
                map.put(field.getName(), value);
            }
        }
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            collectFields(bean, superclass, map);
        }
    }

    /**
     * 获取财产值
     *
     * @param bean Bean
     * @param field 字段
     * @return 获取财产值的结果
     */
    private static Object getPropertyValue(Object bean, String field) {
        try {
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            Object result = ReflectUtils.invoke(bean, getter, Object.class);
            if (result != null) {
                return result;
            }
            String isGetter = "is" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            return ReflectUtils.invoke(bean, isGetter, Object.class);
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 转为camel大小写
     *
     * @param name 名称
     * @return 转为camel大小写的结果
     */
    private static String toCamelCase(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 设置字段值
     *
     * @param obj obj
     * @param field 字段
     * @param value 值
     */
    private void setFieldValue(Object obj, String field, Object value) {
        try {
            String camelField = toCamelCase(field);
            String setterName = "set"
                    + Character.toUpperCase(camelField.charAt(0))
                    + camelField.substring(1);
            ReflectUtils.invoke(obj, setterName, void.class, new Class<?>[]{Object.class}, convertValue(value, Object.class));
        } catch (Exception ignored) {
            // silent
        }
    }

     /**
      * 转换值。
      * @param value 值
      * @param targetType 目标类型
      * @return 转换值的结果
      */
    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null || targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (value instanceof String str && !str.isBlank()) {
            if (targetType == Integer.class || targetType == int.class) {
                return Integer.valueOf(str);
            }
            if (targetType == Long.class || targetType == long.class) {
                return Long.valueOf(str);
            }
            if (targetType == Double.class || targetType == double.class) {
                return Double.valueOf(str);
            }
            if (targetType == Float.class || targetType == float.class) {
                return Float.valueOf(str);
            }
            if (targetType == Short.class || targetType == short.class) {
                return Short.valueOf(str);
            }
            if (targetType == Byte.class || targetType == byte.class) {
                return Byte.valueOf(str);
            }
            if (targetType == Boolean.class || targetType == boolean.class) {
                return Boolean.parseBoolean(str);
            }
        }
        if (value instanceof Number num) {
            if (targetType == Integer.class || targetType == int.class) {
                return num.intValue();
            }
            if (targetType == Long.class || targetType == long.class) {
                return num.longValue();
            }
            if (targetType == Double.class || targetType == double.class) {
                return num.doubleValue();
            }
            if (targetType == Float.class || targetType == float.class) {
                return num.floatValue();
            }
        }
        return value;
    }

    /**
     * 上限Slice。
     *
     * @param data 数据，不允许为 null
     * @param limit 上限，不允许为 null
     * @param offset 偏移量，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private static <T> List<T> limitSlice(List<T> data, int limit, int offset) {
        if (limit <= 0 && offset <= 0) {
            return data;
        }
        int from = Math.min(offset, data.size());
        int to = limit > 0 ? Math.min(from + limit, data.size()) : data.size();
        return from >= data.size() ? Collections.emptyList() : data.subList(from, to);
    }
}

