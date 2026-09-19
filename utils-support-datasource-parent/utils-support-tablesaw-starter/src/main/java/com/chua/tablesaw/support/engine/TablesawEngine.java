package com.chua.tablesaw.support.engine;

import com.chua.common.support.lang.datasource.dialect.Dialect;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.engine.executor.SqlExecutor;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.SFunction;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.FloatColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.Row;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.io.csv.CsvReadOptions;

import java.io.File;
import java.io.InputStream;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Tablesaw 数据引擎实现，基于 Tablesaw 数据处理引擎提供文件数据源的 ORM 查询能力。
 * <p>
 * 通过 {@link #load(String, String)} 加载 CSV 文件，使用 lambda查询包装器 / lambda更新包装器
 * 提供标准的 Engine ORM 接口，完整支持条件过滤、排序、分页等功能。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("tablesaw")
public class TablesawEngine implements Engine {

    /**
     * Tablesaw 表格数据存储映射表
     * <p>
     * 键 为数据源名称或表名，值 为对应的 Tablesaw Table 对象。
     * </p>
     */
    private final Map<String, Table> tables = new ConcurrentHashMap<>();

    /**
     * 引擎数据源映射表
     * <p>
     * 存储所有通过 {@link #addDataSource(String, EngineDataSource)} 注册的数据源。
     * </p>
     */
    private final Map<String, EngineDataSource<Object>> dataSources = new ConcurrentHashMap<>();

    /**
     * 默认数据源名称
     * <p>
     * 当未指定数据源名称时，使用此名称对应的数据源进行操作。
     * </p>
     */
    private String defaultDataSourceName;

    @Override
    /**
     * 添加数据源
    */
    public <T> Engine addDataSource(String name, EngineDataSource<T> dataSource) {
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

    /**
     * 获取默认数据源名称。
     *
     * @return 默认数据源名称
     */
    @Override
    public String getDefaultDataSourceName() {
        return defaultDataSourceName;
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 将数据列表存储到引擎内存中。
     * <p>通过反射读取实体 getter 属性，按返回类型构建 Tablesaw 列并填充数据，
     * 写入 {@code tables} 后即可被 {@code query(Class)} 链式查询检索；
     * 数值/布尔按对应数值列存储，其余类型以字符串形式存储。</p>
     *
     * @param name 数据存储名称（表名）
     * @param data 数据列表
     * @param <T>  数据类型
     * @return this
     */
    public <T> Engine store(String name, List<T> data) {
        if (CollectionUtils.isEmpty(data)) {
            return this;
        }
        Class<T> entityClass = (Class<T>) data.getFirst().getClass();
        // 收集实体类的 getter 属性（get/is 前缀，无参方法）
        Map<String, Method> getters = new LinkedHashMap<>();
        for (Method method : entityClass.getMethods()) {
            String methodName = method.getName();
            if (method.getParameterCount() != 0 || "getClass".equals(methodName)) {
                continue;
            }
            String prop = null;
            if (methodName.startsWith("get") && methodName.length() > 3) {
                prop = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (methodName.startsWith("is") && method.getReturnType() == boolean.class && methodName.length() > 2) {
                prop = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            }
            if (prop != null) {
                getters.put(prop, method);
            }
        }
        // 按属性返回类型创建列并逐行填充
        Table table = Table.create(name);
        for (Map.Entry<String, Method> entry : getters.entrySet()) {
            String column = entry.getKey();
            Method getter = entry.getValue();
            Class<?> type = getter.getReturnType();
            if (type == int.class || type == Integer.class) {
                IntColumn col = IntColumn.create(column);
                for (T entity : data) {
                    Object value = invokeGetter(entity, getter);
                    if (value == null) {
                        col.appendMissing();
                    } else {
                        col.append(((Number) value).intValue());
                    }
                }
                table.addColumns(col);
            } else if (type == long.class || type == Long.class) {
                LongColumn col = LongColumn.create(column);
                for (T entity : data) {
                    Object value = invokeGetter(entity, getter);
                    if (value == null) {
                        col.appendMissing();
                    } else {
                        col.append(((Number) value).longValue());
                    }
                }
                table.addColumns(col);
            } else if (type == double.class || type == Double.class) {
                DoubleColumn col = DoubleColumn.create(column);
                for (T entity : data) {
                    Object value = invokeGetter(entity, getter);
                    if (value == null) {
                        col.appendMissing();
                    } else {
                        col.append(((Number) value).doubleValue());
                    }
                }
                table.addColumns(col);
            } else if (type == float.class || type == Float.class) {
                FloatColumn col = FloatColumn.create(column);
                for (T entity : data) {
                    Object value = invokeGetter(entity, getter);
                    if (value == null) {
                        col.appendMissing();
                    } else {
                        col.append(((Number) value).floatValue());
                    }
                }
                table.addColumns(col);
            } else if (type == boolean.class || type == Boolean.class) {
                BooleanColumn col = BooleanColumn.create(column);
                for (T entity : data) {
                    Object value = invokeGetter(entity, getter);
                    if (value == null) {
                        col.appendMissing();
                    } else {
                        col.append((Boolean) value);
                    }
                }
                table.addColumns(col);
            } else {
                StringColumn col = StringColumn.create(column);
                for (T entity : data) {
                    Object value = invokeGetter(entity, getter);
                    if (value == null) {
                        col.appendMissing();
                    } else {
                        col.append(String.valueOf(value));
                    }
                }
                table.addColumns(col);
            }
        }
        tables.put(name, table);
        if (defaultDataSourceName == null) {
            defaultDataSourceName = name;
        }
        log.info("Tablesaw 数据存储成功: name={}, rows={}, cols={}", name, table.rowCount(), table.columnCount());
        return this;
    }

    /**
     * 反射调用 getter 方法获取属性值。
     *
     * @param entity 实体对象
     * @param getter getter 方法
     * @return 属性值，调用失败返回 null
     */
    private static Object invokeGetter(Object entity, Method getter) {
        try {
            return ReflectUtils.invoke(entity, getter.getName(), Object.class);
        } catch (Exception e) {
            return null;
        }
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
     * 获取数据源
    */
    public <T> EngineDataSource<T> getDataSource(String name) {
        return (EngineDataSource<T>) dataSources.get(name);
    }

    @Override
    /**
     * 获取数据源
    */
    public <T> EngineDataSource<T> getDataSource() {
        return (EngineDataSource<T>) dataSources.get(defaultDataSourceName);
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
     * 不支持元数据操作
    */
    public boolean supportsMeta() {
        return false;
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        tables.clear();
        dataSources.clear();
    }

    /**
     * 加载 CSV 文件到指定名称的数据源
     *
     * @param name    数据源名称
     * @param csvPath CSV 文件路径
     * @return 当前引擎实例，支持链式调用
     */
    public TablesawEngine load(String name, String csvPath) {
        return load(name, csvPath, null);
    }

    /**
     * 加载指定编码的 CSV 文件到数据源
     *
     * @param name    数据源名称
     * @param csvPath CSV 文件路径
     * @param charset 文件编码（如 UTF-8、GBK），为 空 时使用系统默认编码
     * @return 当前引擎实例，支持链式调用
     */
    public TablesawEngine load(String name, String csvPath, String charset) {
        try {
            Table table;
            if (charset != null && !charset.isBlank()) {
                log.debug("使用指定编码加载 CSV: charset={}, path={}", charset, csvPath);
                CsvReadOptions options = CsvReadOptions.builder(
                        new java.io.InputStreamReader(
                                new java.io.FileInputStream(csvPath), Charset.forName(charset))).build();
                table = Table.read().csv(options);
            } else {
                table = Table.read().csv(new File(csvPath));
            }
            tables.put(name, table);
            log.info("Tablesaw 加载 CSV 成功: name={}, rows={}, cols={}", name, table.rowCount(), table.columnCount());
            if (defaultDataSourceName == null) {
                defaultDataSourceName = name;
            }
        } catch (Exception e) {
            throw new RuntimeException("Tablesaw 加载 CSV 失败: " + csvPath, e);
        }
        return this;
    }

    /**
     * 从输入流加载 CSV 数据到数据源
     *
     * @param name        数据源名称
     * @param inputStream CSV 数据输入流
     * @return 当前引擎实例，支持链式调用
     */
    public TablesawEngine load(String name, InputStream inputStream) {
        try {
            Table table = Table.read().csv(inputStream);
            tables.put(name, table);
            log.info("Tablesaw 加载流数据成功: name={}, rows={}, cols={}", name, table.rowCount(), table.columnCount());
            if (defaultDataSourceName == null) {
                defaultDataSourceName = name;
            }
        } catch (Exception e) {
            throw new RuntimeException("Tablesaw 加载流数据失败", e);
        }
        return this;
    }

    /**
     * 获取指定名称的 Tablesaw 表格
     *
     * @param name 表名或数据源名称
     * @return Tablesaw Table 对象，不存在则返回 空
     */
    public Table getTable(String name) {
        return tables.get(name);
    }

    /**
     * 获取默认 Tablesaw 表格
     *
     * @return 默认的 Tablesaw Table 对象
     */
    public Table getTable() {
        return tables.get(defaultDataSourceName);
    }

    /**
     * 获取所有已加载的表名
     *
     * @return 表名集合
     */
    public Set<String> tableNames() {
        return tables.keySet();
    }

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
            protected String resolveColumn(SFunction<T, ?> column) {
                return resolveLambdaColumn(column);
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
                    protected String resolveColumn(SFunction<T, ?> column) {
                        return resolveLambdaColumn(column);
                    }
                };
            }

            @Override
            /**
             * 列表
            */
            public List<T> list() {
                return executeQuery(this);
            }

            @Override
            /**
             * One
            */
            public T one() {
                List<T> r = executeQuery(this);
                if (r.isEmpty()) {
                    return null;
                }
                return r.getFirst();
            }

            @Override
            /**
             * Page
            */
            public Page<T> page(int pageNum, int pageSize) {
                return executePage(this, pageNum, pageSize);
            }
        };
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
            protected String resolveColumn(SFunction<T, ?> column) {
                return resolveLambdaColumn(column);
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
                    protected String resolveColumn(SFunction<T, ?> column) {
                        return resolveLambdaColumn(column);
                    }
                };
            }

            @Override
            /**
             * 更新
            */
            public int update() {
                return 0;
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
            protected String resolveColumn(SFunction<T, ?> column) {
                return resolveLambdaColumn(column);
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
                    protected String resolveColumn(SFunction<T, ?> column) {
                        return resolveLambdaColumn(column);
                    }
                };
            }

            @Override
            /**
             * 移除
            */
            public int remove() {
                return 0;
            }
        };
    }

    /**
     * 解析 Lambda 表达式中的方法引用为属性名
     * <p>
     * 例如 {@code User::getName} 解析为 {@code "name"}。
     * </p>
     *
     * @param column Lambda 方法引用
     * @return 属性名字符串，解析失败返回 空
     */
    private String resolveLambdaColumn(SFunction<?, ?> column) {
        if (column == null) {
            return null;
        }
        try {
            SerializedLambda lambda = (SerializedLambda) ReflectUtils.invoke(column, "writeReplace", SerializedLambda.class);
            String name = lambda.getImplMethodName();
            if (name.startsWith("is")) {
                name = name.substring(2);
            } else if (name.startsWith("get") || name.startsWith("set")) {
                name = name.substring(3);
            } else {
                return null;
            }
            if (name.length() == 1 || (name.length() > 1 && !Character.isUpperCase(name.charAt(1)))) {
                name = name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
            }
            return name;
        } catch (Exception e) {
            log.warn("Lambda 列名解析失败", e);
            return null;
        }
    }

    /**
     * 执行查询操作
     * <p>
     * 先加载全部数据，然后根据条件过滤，最后排序。
     * </p>
     *
     * @param wrapper 查询包装器
     * @param <T>     实体类型
     * @return 查询结果列表
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> executeQuery(LambdaQueryWrapper<T> wrapper) {
        Table table = resolveTable(wrapper.getEntityClass());
        if (table == null) {
            log.warn("未找到对应的表: entityClass={}", wrapper.getEntityClass().getSimpleName());
            return Collections.emptyList();
        }

        // 解析列名到 setter 方法的映射
        List<String> columnNames = table.columnNames();
        Map<String, Method> setters = resolveSetters(wrapper.getEntityClass(), columnNames);

        // 将 Tablesaw Row 转换为实体对象
        List<T> all = new ArrayList<>(table.rowCount());
        for (Row row : table) {
            all.add(rowToEntity(row, wrapper.getEntityClass(), setters, columnNames));
        }

        // 应用条件过滤
        List<Condition> conditions = wrapper.getConditions();
        if (CollectionUtils.isNotEmpty(conditions)) {
            Predicate<T> predicate = conditionsToPredicate(conditions);
            all = all.stream().filter(predicate).collect(Collectors.toList());
        }

        // 应用排序
        List<String> orderBys = wrapper.getOrderBys();
        if (CollectionUtils.isNotEmpty(orderBys)) {
            all.sort((a, b) -> compareOrdered(a, b, orderBys));
        }

        return all;
    }

    /**
     * 比较两个对象在指定字段上的大小
     * <p>
     * 支持多字段排序，按 订单bys 列表顺序逐字段比较。
     * </p>
     *
     * @param a        对象 A
     * @param b        对象 B
     * @param orderBys 排序字段列表，格式为 "字段名称 ASC" 或 "字段名称 DESC"
     * @param <T>      对象类型
     * @return 负数表示 a < b，正数表示 a > b，相等返回 0
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> int compareOrdered(T a, T b, List<String> orderBys) {
        for (String ob : orderBys) {
            String[] parts = ob.split(" ");
            String field = parts[0];
            boolean desc = parts.length > 1 && "DESC".equalsIgnoreCase(parts[1]);
            Object va = getPropertyValue(a, field);
            Object vb = getPropertyValue(b, field);
            int cmp;
            if (va == null && vb == null) {
                cmp = 0;
            } else if (va == null) {
                cmp = -1;
            } else if (vb == null) {
                cmp = 1;
            } else if (va instanceof Comparable && vb instanceof Comparable) {
                cmp = ((Comparable) va).compareTo(vb);
            } else {
                cmp = va.toString().compareTo(vb.toString());
            }
            if (cmp != 0) {
                return desc ? -cmp : cmp;
            }
        }
        return 0;
    }

    /**
     * 将条件列表转换为 Predicate
     * <p>
     * 多个条件之间为 和 关系。
     * </p>
     *
     * @param conditions 条件列表
     * @param <T>        实体类型
     * @return 组合后的 Predicate
     */
    private <T> Predicate<T> conditionsToPredicate(List<Condition> conditions) {
        if (CollectionUtils.isEmpty(conditions)) {
            return t -> true;
        }
        List<Predicate<T>> predicates = new ArrayList<>();
        for (Condition c : conditions) {
            predicates.add(conditionToPredicate(c));
        }
        return t -> predicates.stream().allMatch(p -> p.test(t));
    }

    /**
     * 将单个条件转换为 Predicate
     * <p>
     * 支持嵌套条件（和/或 逻辑组合）。
     * </p>
     *
     * @param c   条件对象
     * @param <T> 实体类型
     * @return 对应的 Predicate
     */
    @SuppressWarnings("unchecked")
    private <T> Predicate<T> conditionToPredicate(Condition c) {
        if (c.isNested()) {
            List<Predicate<T>> nested = new ArrayList<>();
            for (Condition sub : c.getNested()) {
                nested.add(conditionToPredicate(sub));
            }
            if ("OR".equalsIgnoreCase(c.getNestedOperator())) {
                return t -> nested.stream().anyMatch(p -> p.test(t));
            }
            return t -> nested.stream().allMatch(p -> p.test(t));
        }
        String col = c.getColumnName();
        String op = c.getOperator();
        Object val = c.getValue();
        return t -> {
            Object prop = getPropertyValue(t, col);
            return evaluate(prop, op, val);
        };
    }

    /**
     * 计算属性值与操作数的匹配结果
     * <p>
     * 支持的操作符：=, !=, >, >=, <, <=, LIKE, NOT LIKE, 入, NOT 入, 是否 空, 是否 NOT 空, BETWEEN。
     * </p>
     *
     * @param prop 属性值
     * @param op   操作符
     * @param val  操作数值
     * @return 匹配结果
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean evaluate(Object prop, String op, Object val) {
        switch (op) {
            case "=":
                return eq(prop, val);
            case "!=":
                return !eq(prop, val);
            case ">":
                if (prop == null || val == null) {
                    return false;
                }
                if (!(prop instanceof Comparable)) {
                    return false;
                }
                return ((Comparable) prop).compareTo(val) > 0;
            case ">=":
                if (prop == null || val == null) {
                    return false;
                }
                if (!(prop instanceof Comparable)) {
                    return false;
                }
                return ((Comparable) prop).compareTo(val) >= 0;
            case "<":
                if (prop == null || val == null) {
                    return false;
                }
                if (!(prop instanceof Comparable)) {
                    return false;
                }
                return ((Comparable) prop).compareTo(val) < 0;
            case "<=":
                if (prop == null || val == null) {
                    return false;
                }
                if (!(prop instanceof Comparable)) {
                    return false;
                }
                return ((Comparable) prop).compareTo(val) <= 0;
            case "LIKE":
                if (prop == null) {
                    return false;
                }
                return likeMatch(String.valueOf(prop), String.valueOf(val));
            case "NOT LIKE":
                if (prop == null) {
                    return true;
                }
                return !likeMatch(String.valueOf(prop), String.valueOf(val));
            case "IN":
                if (prop == null) {
                    return false;
                }
                if (!(val instanceof Collection)) {
                    return false;
                }
                return ((Collection<?>) val).stream().anyMatch(v -> eq(prop, v));
            case "NOT IN":
                if (prop == null) {
                    return true;
                }
                if (!(val instanceof Collection)) {
                    return true;
                }
                return ((Collection<?>) val).stream().noneMatch(v -> eq(prop, v));
            case "IS NULL":
                return prop == null;
            case "IS NOT NULL":
                return prop != null;
            case "BETWEEN":
                if (prop == null) {
                    return false;
                }
                if (!(val instanceof Object[])) {
                    return false;
                }
                if (!(prop instanceof Comparable)) {
                    return false;
                }
                Object[] range = (Object[]) val;
                return ((Comparable) prop).compareTo(range[0]) >= 0
                        && ((Comparable) prop).compareTo(range[1]) <= 0;
            default:
                log.warn("不支持的操作符: {}", op);
                return false;
        }
    }

    /**
     * 判断两个值是否相等
     * <p>
     * 对 数字 类型使用 double 值比较，其他类型使用 转为字符串 兜底。
     * </p>
     *
     * @param prop 属性值
     * @param val  目标值
     * @return 是否相等
     */
    private boolean eq(Object prop, Object val) {
        if (prop == null) {
            return val == null;
        }
        if (val == null) {
            return false;
        }
        if (prop instanceof Number && val instanceof Number) {
            return ((Number) prop).doubleValue() == ((Number) val).doubleValue();
        }
        if (prop.equals(val)) {
            return true;
        }
        return prop.toString().equals(val.toString());
    }

    /**
     * LIKE 模糊匹配
     * <p>
     * 支持 % 通配符：%xxx（结尾匹配）、xxx%（开头匹配）、%xxx%（包含匹配）。
     * </p>
     *
     * @param prop    属性值字符串
     * @param pattern 匹配模式
     * @return 是否匹配
     */
    private boolean likeMatch(String prop, String pattern) {
        String p = pattern.replace("%", "").toLowerCase();
        if (pattern.startsWith("%") && pattern.endsWith("%")) {
            return prop.toLowerCase().contains(p);
        }
        if (pattern.endsWith("%")) {
            return prop.toLowerCase().startsWith(p);
        }
        if (pattern.startsWith("%")) {
            return prop.toLowerCase().endsWith(p);
        }
        return prop.equalsIgnoreCase(p);
    }

    /**
     * 执行分页查询
     *
     * @param wrapper  查询包装器
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页大小
     * @param <T>      实体类型
     * @return 分页结果
     */
    private <T> Page<T> executePage(LambdaQueryWrapper<T> wrapper, int pageNum, int pageSize) {
        List<T> all = executeQuery(wrapper);
        int total = all.size();
        int from = (pageNum - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        List<T> records;
        if (from >= total) {
            records = Collections.emptyList();
        } else {
            records = all.subList(from, to);
        }
        return new Page<>(pageNum, pageSize, total, records);
    }

    /**
     * 根据实体类型解析对应的 Tablesaw 表格
     * <p>
     * 优先查找表名匹配的表格，其次使用默认表格。
     * </p>
     *
     * @param entityClass 实体类类型
     * @return Tablesaw Table 对象，未找到返回 空
     */
    private Table resolveTable(Class<?> entityClass) {
        String name = getTableName(entityClass);
        Table table = tables.get(name);
        if (table == null) {
            table = tables.get(defaultDataSourceName);
        }
        return table;
    }

    /**
     * 将 Tablesaw 行数据转换为实体对象
     *
     * @param row         Tablesaw 行数据
     * @param entityClass 实体类类型
     * @param setters     setter 方法映射表
     * @param columnNames 列名列表
     * @param <T>         实体类型
     * @return 实体对象
     */
    private <T> T rowToEntity(
            Row row,
            Class<T> entityClass,
            Map<String, Method> setters,
            List<String> columnNames) {
        try {
            T instance = ReflectUtils.instantiate(entityClass);
            for (String col : columnNames) {
                Method setter = setters.get(col.toLowerCase());
                if (setter == null) {
                    continue;
                }
                Object value = getRowValue(row, col, setter.getParameterTypes()[0]);
                if (value != null) {
                    ReflectUtils.invoke(instance, setter.getName(), void.class, value);
                }
            }
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Tablesaw 行转实体失败: " + entityClass.getSimpleName(), e);
        }
    }

    /**
     * 从 Tablesaw 行中获取指定列的值并转换类型
     *
     * @param row        Tablesaw 行数据
     * @param column     列名
     * @param targetType 目标类型
     * @return 转换后的值
     */
    private static Object getRowValue(Row row, String column, Class<?> targetType) {
        if (row.isMissing(column)) {
            return null;
        }
        if (targetType.isEnum()) {
            String str = row.getString(column);
            if (str == null) {
                return null;
            }
            for (Object c : targetType.getEnumConstants()) {
                if (c.toString().equalsIgnoreCase(str)) {
                    return c;
                }
            }
        }
        // 统一走 Converter 工具做类型转换，禁止手写逐类型分支（P3C 四十二）
        Object converted = Converter.convertIfNecessary(row.getObject(column), targetType);
        if (converted != null) {
            return converted;
        }
        return row.getObject(column);
    }

    /**
     * 解析实体类的 setter 方法映射
     * <p>
     * 根据 CSV 列名匹配对应实体类的 setter 方法，列名不区分大小写。
     * </p>
     *
     * @param entityClass 实体类类型
     * @param columnNames 列名列表
     * @return 列名小写到 setter 方法的映射表
     */
    private static Map<String, Method> resolveSetters(Class<?> entityClass, List<String> columnNames) {
        Map<String, Method> setters = new HashMap<>();
        Set<String> lowerCols = new HashSet<>();
        for (String col : columnNames) {
            lowerCols.add(col.toLowerCase());
        }
        for (Method method : entityClass.getMethods()) {
            String name = method.getName();
            if (name.length() < 4 || !name.startsWith("set") || method.getParameterCount() != 1) {
                continue;
            }
            String prop = name.substring(3);
            if (prop.isEmpty()) {
                continue;
            }
            String lowerProp = prop.toLowerCase();
            if (lowerCols.contains(lowerProp)) {
                setters.put(lowerProp, method);
            }
        }
        return setters;
    }

    /**
     * 将实体类名转换为表名
     * <p>
     * 驼峰命名转换为下划线命名，例如 {@code UserInfo} 转换为 {@code user_info}。
     * </p>
     *
     * @param entityClass 实体类类型
     * @return 转换后的表名
     */
    private static String getTableName(Class<?> entityClass) {
        String simpleName = entityClass.getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (char c : simpleName.toCharArray()) {
            if (Character.isUpperCase(c) && !sb.isEmpty()) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 通过反射获取对象属性值
     * <p>
     * 优先尝试 获取xxx() 方法，其次尝试 是否xxx() 方法（适用于 布尔值 类型字段）。
     * </p>
     *
     * @param bean  对象实例
     * @param field 字段名
     * @return 属性值，获取失败返回 空
     */
    private static Object getPropertyValue(Object bean, String field) {
        try {
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            for (Method m : bean.getClass().getMethods()) {
                if (m.getName().equals(getter) && m.getParameterCount() == 0) {
                    return ReflectUtils.invoke(bean, m.getName(), Object.class);
                }
            }
            String isGetter = "is" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            for (Method m : bean.getClass().getMethods()) {
                if (m.getName().equals(isGetter) && m.getParameterCount() == 0) {
                    return ReflectUtils.invoke(bean, m.getName(), Object.class);
                }
            }
        } catch (Exception e) {
            log.warn("属性值获取失败: field={}", field, e);
        }
        return null;
    }
}
