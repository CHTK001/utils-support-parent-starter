package com.chua.datasource.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.interceptor.EngineInterceptor;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.DeleteSql;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaUpdateWrapper;
import com.chua.common.support.lang.datasource.engine.wrapper.UpdateSql;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.MemorySqlAst.DeletePlan;
import com.chua.datasource.support.engine.MemorySqlAst.DmlPlan;
import com.chua.datasource.support.engine.MemorySqlAst.InsertPlan;
import com.chua.datasource.support.engine.MemorySqlAst.UpdatePlan;
import com.chua.datasource.support.wrapper.EngineDeleteWrapper;
import com.chua.datasource.support.wrapper.EngineQueryWrapper;
import com.chua.datasource.support.wrapper.EngineUpdateWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内存引擎实现，基于 并发哈希映射 提供表级别的轻量数据存储与查询。
 * <p>
 * 支持二级索引加速等值查询（{@code =} 条件走索引，否则回退全表扫描）。
 * 通过 SPI 注册为 {@code "memory"}，可作为单元测试或无持久化场景下的默认 Engine。
 * </p>
 *
 * <p><b>并发语义</b>：表数据（{@code dataStores}）与二级索引（{@code indexes}）
     * 均通过安全发布供多个线程读取；索引在本地构建完成后一次发布。
     * 写入与索引重建互斥，写入前后均失效目标表索引，查询热路径不取得该互斥锁。
     * 读取端校验索引所属行集合的身份，未命中时自动回退全表扫描。
 * 但单表内部的复合操作（如「先查再改」）不构成原子单元，需要原子性时
 * 请由调用方自行加锁或在事务上下文中使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("memory")
public class InMemoryEngine extends AbstractEngine {

    /**
     * 索引用 null 占位，{@code ConcurrentHashMap} 不允许空键，处理 null 字段值
     */
    private static final Object NULL_IDX_KEY = new Object();

    /**
     * indexes
    */
    private final Map<String, TableIndex> indexes = new ConcurrentHashMap<>();

    /** 仅串行化写入与索引发布，不阻塞已发布索引上的读取。 */
    private final Object indexMutationLock = new Object();

    /** 索引与实际存储行集合绑定，防止换表期间读到其他版本的候选行。 */
    private record TableIndex(List<?> rows, Map<String, Map<Object, List<Object>>> fields) {
    }

    /** 写入与重建共用边界，finally 同时覆盖部分写入后抛异常的路径。 */
    private <T> T mutate(String table, Supplier<T> action) {
        synchronized (indexMutationLock) {
            invalidateIndexes(table);
            try {
                return action.get();
            } finally {
                invalidateIndexes(table);
            }
        }
    }

    /**
     * 存储
     *
     * <p>整体替换目标表数据后，旧索引指向的行集合作废，
     * 必须先摘除该表索引，避免 {@link #tryIndexLookup} 命中已失效的行引用。</p>
     */
    @Override
    public <T> Engine store(String name, List<T> data) {
        return mutate(name, () -> super.store(name, data));
    }

    /**
     * 为当前全部已存表构建二级索引，加速后续等值条件查询。
     *
     * @return 当前引擎实例
     */
    public InMemoryEngine index() {
        synchronized (indexMutationLock) {
            for (Map.Entry<String, List<?>> e : dataStores.entrySet()) {
                buildIndex(e.getKey(), e.getValue());
            }
        }
        return this;
    }

    /**
     * 使指定表及其数据存储的二级索引失效。
     *
     * <p>索引按 {@code dataStores} 的实际键构建，而 {@link #getData} 在表名缺失时
     * 回退到 {@code "default"} 存储，因此两个键都需要摘除，缺一都会留下
     * 指向旧数据集的悬空索引。摘除后下次等值查询经 {@link #tryIndexLookup}
     * 未命中索引，自动回退全表扫描，不影响结果正确性。</p>
     *
     * @param table 表名
     */
    private void invalidateIndexes(String table) {
        indexes.remove(table);
        indexes.remove("default");
    }

    /**
     * 构建索引
     *
     * @param name 名称
     * @param data 数据
     */
    private <T> void buildIndex(String name, List<T> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) data;
        T first = data.getFirst();
        Map<String, Map<Object, List<Object>>> tableIdx = Wraps.map();
        List<String> fields = getGetters(first.getClass());
        for (Object row : rows) {
            for (String field : fields) {
                Object val = MethodCache.getValue(row, field);
                Map<Object, List<Object>> fieldIdx = tableIdx.computeIfAbsent(field, k -> Wraps.map());
                // null 字段值不能入 ConcurrentHashMap（空键 NPE），统一用占位键
                List<Object> idxRows = fieldIdx.get(val == null ? NULL_IDX_KEY : val);
                if (idxRows == null) {
                    idxRows = Wraps.list();
                    fieldIdx.put(val == null ? NULL_IDX_KEY : val, idxRows);
                }
                idxRows.add(row);
            }
        }
        indexes.put(name, new TableIndex(data, tableIdx));
    }

    /**
     * 获取Getters
     *
     * @param clazz clazz
     * @return 获取getters的结果
     */
    private static List<String> getGetters(Class<?> clazz) {
        List<String> fields = Wraps.list();
        for (var m : clazz.getMethods()) {
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class) {
                continue;
            }
            String n = m.getName();
            if (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3))) {
                fields.add(Character.toLowerCase(n.charAt(3)) + n.substring(4));
            } else if (n.startsWith("is") && n.length() > 2 && Character.isUpperCase(n.charAt(2))) {
                fields.add(Character.toLowerCase(n.charAt(2)) + n.substring(3));
            } else if (!n.startsWith("get") && !n.startsWith("is") && !n.startsWith("set")
                    && !"hashCode".equals(n) && !"toString".equals(n) && !"equals".equals(n)) {
                fields.add(n);
            }
        }
        return fields;
    }

    /**
     * 查询
    */
    @Override
    public <T> LambdaQueryWrapper<T> query(Class<T> entityClass) {
        return new InMemQueryWrapper<>(entityClass);
    }

    /**
     * 更新
    */
    @Override
    public <T> LambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return new InMemUpdateWrapper<>(entityClass);
    }

    /**
     * 删除
    */
    @Override
    public <T> LambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return new InMemDeleteWrapper<>(entityClass);
    }

    /**
     * 执行新查询
    */
    @Override
    protected <T> List<T> executeNewQuery(String where, Object[] args, Class<T> clazz, int limit, int offset) {
        throw new UnsupportedOperationException();
    }

    /**
     * 执行原生 选择：SQL 编译为 AST 后在表行引用上求值。
     *
     * <p>本引擎没有 {@code SqlExecutor}，原生语句能力完全由本方法提供，
     * 因此必须覆写接口规范入口 {@link Engine#querySql(String, Object...)}；
     * 执行前后由基类的 {@code interceptQuery} 统一回调 {@link EngineInterceptor}。</p>
     *
     * @param sql    选择 语句（支持 WHERE/订单 BY/限制/数量(*)）
     * @param params ? 绑定参数
     * @return 结果行
     */
    @Override
    public List<Map<String, Object>> querySql(String sql, Object... params) {
        // 空语句是调用方用法错误，必须给出可定位的 IllegalArgumentException；
        // 若直接进入 extractTable，Pattern.matcher(null) 会抛无消息的裸 NPE，
        // 调用方无法区分「用法错误」与「引擎内部缺陷」。
        if (sql == null) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        Object[] args = params == null ? new Object[0] : params;
        String table = extractTable(sql);
        List<?> rows = dataStores.getOrDefault(table, Collections.emptyList());
        return interceptQuery(sql, args, () -> new MemorySqlParser().executeQuery(sql, rows, args));
    }

    /**
     * 执行原生查询并按行类型映射。
     *
     * <p>内存引擎没有 {@link SqlExecutor}，接口默认实现会按
     * {@code getExecutor() == null} 直接抛不支持异常 —— 但本引擎
     * {@link #supportsSql()} 为 true 且已具备完整语句通道，因此必须覆写本方法，
     * 否则能力声明与实际行为不一致。映射复用与
     * {@code InMemoryReactorEngine} 同一套 {@link MemorySqlLex.RowAccessor#toBean}。</p>
     *
     * @param ql      查询语句，不允许为 null
     * @param rowType 行类型，不允许为 null
     * @param params  绑定参数，可为 null（按无参处理）
     * @param <T>     行类型
     * @return 类型化结果列表
     * @throws IllegalArgumentException 语句或行类型为 null 时抛出
     */
    @Override
    public <T> List<T> querySql(String ql, Class<T> rowType, Object... params) {
        if (ql == null) {
            throw new IllegalArgumentException("ql 不能为空");
        }
        if (rowType == null) {
            throw new IllegalArgumentException("rowType 不能为空");
        }
        List<Map<String, Object>> rows = querySql(ql, params);
        List<T> out = Wraps.list(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(MemorySqlLex.RowAccessor.toBean(row, rowType));
        }
        return out;
    }

    /**
     * 执行原生 DML：插入 / 更新 / 删除 直接作用于表行引用。
     *
     * <p>覆写接口规范入口 {@link Engine#executeSql(String, Object...)}，
     * 执行前后由基类的 {@code interceptUpdate} 统一回调 {@link EngineInterceptor}。</p>
     *
     * @param sql    DML 语句
     * @param params ? 绑定参数
     * @return 影响行数
     */
    @Override
    public int executeSql(String sql, Object... params) {
        Objects.requireNonNull(sql, "sql must not be null");
        Object[] args = params == null ? new Object[0] : params;
        return interceptUpdate(sql, args, () -> {
            var plan = new MemorySqlParser().parseDml(sql);
            return mutate(plan.table(), () -> MemorySqlAst.executeDml(plan,
                    java.util.Arrays.asList(args),
                    () -> mutableRowsFor(plan.table())));
        });
    }

    /**
     * 是否支持原生语句执行
     * <p>内存引擎自带完整 SQL 解析与求值链路（{@link MemorySqlParser}），
     * 只是没有 JDBC 执行器，因此必须显式覆写返回 true，
     * 否则调用方会按 {@code getExecutor() == null} 误判为不支持 SQL。</p>
     *
     * @return 恒为 true
     */
    @Override
    public boolean supportsSql() {
        return true;
    }

    /**
     * 基于内存表执行更新：先摘除目标表索引，再委托基类内存更新。
     *
     * <p>基类 {@link AbstractEngine#executeUpdate} 走 {@code executeUpdateInMemory}，
     * 直接改写行对象字段，索引引用同时失效，因此必须随写失效，否则
     * {@link #tryIndexLookup} 仍按旧值命中行、查询结果与真实数据不一致。</p>
     */
    @Override
    public <T> int executeUpdate(UpdateSql<T> sql) {
        return mutate(getTableName(sql.entityClass()), () -> super.executeUpdate(sql));
    }

    /**
     * 基于内存表执行删除：先摘除目标表索引，再委托基类内存删除。
     *
     * <p>基类删除会整体替换目标表行集合，索引指向旧集合引用，删除后成为悬空索引，
     * 必须随写失效，否则等值查询会命中已删除的行对象。</p>
     */
    @Override
    public <T> int executeDelete(DeleteSql<T> sql) {
        return mutate(getTableName(sql.entityClass()), () -> super.executeDelete(sql));
    }

    @Override
    public <T> int insert(Class<T> entityClass, T entity) {
        return mutate(getTableName(entityClass), () -> super.insert(entityClass, entity));
    }

    @Override
    public <T> int insertBatch(Class<T> entityClass, List<T> entities) {
        return mutate(getTableName(entityClass), () -> super.insertBatch(entityClass, entities));
    }

    /**
     * 内存通道插入完成后的收尾钩子：新行进入表后，旧索引不包含它们，
     * 必须随写失效，否则新行的等值查询会漏命（索引未命中自动回退全表扫描）。
     *
     * @param entityClass 实体类类型
     * @param affected    本次插入行数
     */
    @Override
    protected void afterMemoryInsert(Class<?> entityClass, int affected) {
        invalidateIndexes(getTableName(entityClass));
    }

    /**
     * 获取指定表的可变行引用，表不存在时创建空表挂载。
     *
     * @param table 表名
     * @return 可变行引用列表
     */
    private List<Object> mutableRowsFor(String table) {
        dataStores.computeIfAbsent(table, k -> Wraps.list());
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) dataStores.get(table);
        return rows;
    }

    /**
     * 提取 从 子句后的表名，供 选择 定位数据。
     *
     * @param sql 选择 语句
     * @return 表名
     * @throws IllegalArgumentException 缺少 从 子句时抛出
     */
    /**
     * 匹配 FROM 子句后表名的预编译正则；extractTable 位于查询热路径，
     * 预编译复用可避免每次调用重复 {@code Pattern.compile}。
     */
    private static final Pattern FROM_TABLE_PATTERN = Pattern.compile("(?i)FROM\\s+([\\w]+)");

    private static String extractTable(String sql) {
        Matcher m = FROM_TABLE_PATTERN.matcher(sql);
        if (!m.find()) {
            throw new IllegalArgumentException("缺少 FROM 子句: " + sql);
        }
        return m.group(1);
    }

    /**
     * evaluate查询。
     *
     * @param wrapper 方法入参 wrapper
     * @return 结果列表，无数据时为空列表
     */
    @SuppressWarnings("unchecked")
    <T> List<T> evaluateQuery(EngineQueryWrapper<T> wrapper) {
        List<T> data = getData(wrapper.getEntityClass());
        if (data.isEmpty()) {
            return data;
        }
        List<Condition> conditions = wrapper.getConditions();
        List<T> result;
        if (conditions.isEmpty()) {
            result = data;
        } else {
            List<T> candidates = tryIndexLookup(wrapper.getEntityClass(), conditions, data);
            if (candidates.isEmpty()) {
                return candidates;
            }
            Predicate<T> predicate = buildPredicate(conditions);
            result = candidates.stream().filter(predicate).toList();
        }
        List<String> orderBys = wrapper.getOrderBys();
        if (!orderBys.isEmpty()) {
            result = Wraps.list(result);
            result.sort((a, b) -> compareOrdered(a, b, orderBys));
        }
        return result;
    }

    /**
     * 尝试索引lookup
     *
     * @param clazz clazz
     * @param conditions 条件
     * @param data 数据
     * @return 尝试索引lookup的结果
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> tryIndexLookup(Class<T> clazz, List<Condition> conditions, List<T> data) {
        Condition first = conditions.getFirst();
        if (!"=".equals(first.getOperator()) || conditions.stream().anyMatch(c -> c.isNested()
                && "OR".equalsIgnoreCase(c.getNestedOperator()))) {
            return data;
        }
        String field = first.getColumnName();
        String tableName = getTableName(clazz);
        TableIndex tableIdx = indexes.get(tableName);
        if (tableIdx == null || tableIdx.rows() != data) {
            tableIdx = indexes.get("default");
        }
        if (tableIdx == null || tableIdx.rows() != data) {
            return data;
        }
        Map<Object, List<Object>> fieldIdx = tableIdx.fields().get(field);
        if (fieldIdx == null) {
            return data;
        }
        List<Object> matched = fieldIdx.getOrDefault(first.getValue() == null
                ? NULL_IDX_KEY : first.getValue(), Collections.emptyList());
        if (matched.isEmpty()) {
            return Collections.emptyList();
        }
        return (List<T>) matched;
    }

    /**
     * evaluate页。
     *
     * @param wrapper 方法入参 wrapper
     * @param pn 方法入参 pn
     * @param ps 方法入参 ps
     * @return 页 对象
     */
    <T> Page<T> evaluatePage(EngineQueryWrapper<T> wrapper, int pn, int ps) {
        List<T> all = evaluateQuery(wrapper);
        int from = (pn - 1) * ps;
        int to = Math.min(from + ps, all.size());
        if (from >= all.size()) {
            return new Page<>(pn, ps, all.size(), Collections.emptyList());
        }
        // 独立副本，避免 subList 视图持有内存存储引用
        return new Page<>(pn, ps, all.size(), Wraps.list(all.subList(from, to)));
    }

    /**
     * evaluate更新。
     *
     * @param wrapper 方法入参 wrapper
     * @return 结果数值
     */
    <T> int evaluateUpdate(EngineUpdateWrapper<T> wrapper) {
        return mutate(getTableName(wrapper.getEntityClass()), () -> evaluateUpdateRows(wrapper));
    }

    private <T> int evaluateUpdateRows(EngineUpdateWrapper<T> wrapper) {
        // 内存求值不经过 buildSql()，必须在此复用同一套全表守卫，
        // 否则无 WHERE 的 update() 会因空条件谓词恒真而更新整表（静默数据破坏）。
        wrapper.requireWhereCondition("更新");
        List<T> data = getData(wrapper.getEntityClass());
        if (data.isEmpty()) {
            return 0;
        }
        // 逐行改写字段，索引引用随之失效，先摘除目标表索引
        invalidateIndexes(getTableName(wrapper.getEntityClass()));
        Predicate<T> predicate = buildPredicate(wrapper.getConditions());
        Map<String, Object> setValues = wrapper.getSetValues();
        if (setValues.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (T item : data) {
            if (predicate.test(item)) {
                for (Map.Entry<String, Object> e : setValues.entrySet()) {
                    MethodCache.setValue(item, e.getKey(), e.getValue());
                }
                count++;
            }
        }
        return count;
    }

    /**
     * evaluate删除。
     *
     * @param wrapper 方法入参 wrapper
     * @return 结果数值
     */
    <T> int evaluateDelete(EngineDeleteWrapper<T> wrapper) {
        return mutate(getTableName(wrapper.getEntityClass()), () -> evaluateDeleteRows(wrapper));
    }

    private <T> int evaluateDeleteRows(EngineDeleteWrapper<T> wrapper) {
        // 与 evaluateUpdate 同理：空条件谓词取反后恒假，会删除整表。
        wrapper.requireWhereCondition("删除");
        List<T> data = getData(wrapper.getEntityClass());
        if (data.isEmpty()) {
            return 0;
        }
        // 删除会整体替换目标表行集合，索引指向旧集合引用，先摘除目标表索引
        invalidateIndexes(getTableName(wrapper.getEntityClass()));
        Predicate<T> predicate = buildPredicate(wrapper.getConditions());
        int before = data.size();
        // 必须包成可变列表：toList() 产出不可变列表，替换进 dataStores 后，
        // 任何结构性写操作（executeSql DELETE 的 removeIf、内存通道 insert 的 add）都会抛 UOE。
        List<T> remaining = Wraps.list(data.stream().filter(predicate.negate()).toList());
        int removed = before - remaining.size();
        if (removed > 0) {
            for (Map.Entry<String, List<?>> entry : dataStores.entrySet()) {
                if (entry.getValue() == data) {
                    dataStores.put(entry.getKey(), remaining);
                }
            }
        }
        return removed;
    }

    @Override
    public void close() {
        synchronized (indexMutationLock) {
            indexes.clear();
            super.close();
        }
    }

    /**
     * 构建Predicate
     *
     * @param conditions 条件
     * @return 构建predicate的结果
     */
    private static <T> Predicate<T> buildPredicate(List<Condition> conditions) {
        if (conditions.isEmpty()) {
            return t -> true;
        }
        // 与 AbstractLambdaWrapper.buildWhere 的连接规则保持一致：
        // OR 嵌套分组与前文以 OR 连接，其余条件一律 AND。
        Predicate<T> result = toPredicate(conditions.getFirst());
        for (int i = 1; i < conditions.size(); i++) {
            Condition current = conditions.get(i);
            boolean leadingOr = current.isNested()
                    && "OR".equalsIgnoreCase(current.getNestedOperator());
            Predicate<T> next = toPredicate(current);
            result = leadingOr ? result.or(next) : result.and(next);
        }
        return result;
    }

    /**
     * 转为predicate
     *
     * @param c c
     * @return 转为predicate的结果
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Predicate<T> toPredicate(Condition c) {
        // 嵌套条件（{@code and(Consumer)} / {@code or(Consumer)} 产生）没有 operator，
        // 必须先按 nestedOperator 折叠子条件，否则 switch(null) 抛 NPE。
        if (c.isNested()) {
            List<Condition> nested = c.getNested();
            if (nested == null || nested.isEmpty()) {
                return t -> true;
            }
            boolean any = "OR".equalsIgnoreCase(c.getNestedOperator());
            Predicate<T> result = null;
            for (Condition child : nested) {
                Predicate<T> childPredicate = toPredicate(child);
                if (result == null) {
                    result = childPredicate;
                } else {
                    result = any ? result.or(childPredicate) : result.and(childPredicate);
                }
            }
            return result == null ? t -> true : result;
        }
        String field = c.getColumnName();
        String op = c.getOperator();
        Object val = c.getValue();
        return switch (op) {
            case "=" -> t -> Objects.equals(MethodCache.getValue(t, field), val);
            case "!=", "<>" -> t -> !Objects.equals(MethodCache.getValue(t, field), val);
            // 字段或参数不可比较时不抛 ClassCastException，默认不命中
            case ">", ">=", "<", "<=" -> t -> {
                Object fieldVal = MethodCache.getValue(t, field);
                Comparable fv = asComparable(fieldVal);
                if (fv == null) {
                    return false;
                }
                // 可比较：参数同类型，或可转换为目标类型
                Comparable cv = compareableOf(fv, val);
                if (cv == null) {
                    return false;
                }
                return switch (op) {
                    case ">" -> fv.compareTo(cv) > 0;
                    case ">=" -> fv.compareTo(cv) >= 0;
                    case "<" -> fv.compareTo(cv) < 0;
                    default -> fv.compareTo(cv) <= 0;
                };
            };
            case "LIKE" -> t -> like(MethodCache.getValue(t, field), val);
            case "IS NULL" -> t -> MethodCache.getValue(t, field) == null;
            case "IS NOT NULL" -> t -> MethodCache.getValue(t, field) != null;
            case "IN" -> t -> val instanceof Collection<?> col
                    && col.contains(MethodCache.getValue(t, field));
            case "NOT IN" -> t -> !(val instanceof Collection<?> col)
                    || !col.contains(MethodCache.getValue(t, field));
            case "BETWEEN" -> {
                Object[] range = val instanceof Object[] arr ? arr : null;
                if (range == null || range.length < 2) {
                    yield t -> false;
                }
                yield t -> {
                    Object fieldVal = MethodCache.getValue(t, field);
                    Comparable fv = asComparable(fieldVal);
                    if (fv == null) {
                        return false;
                    }
                    // 上下界需转为字段类型才可比较，转换失败则不匹配
                    Comparable start = compareableOf(fv, range[0]);
                    Comparable end = compareableOf(fv, range[1]);
                    if (start == null || end == null) {
                        return false;
                    }
                    return fv.compareTo(start) >= 0 && fv.compareTo(end) <= 0;
                };
            }
            default -> t -> true;
        };
    }

    /**
     * 转为 Comparable，取不到（null 或非 Comparable）时返回 null，避免 ClassCastException。
     */
    private static Comparable asComparable(Object value) {
        if (value == null || !(value instanceof Comparable c)) {
            return null;
        }
        return c;
    }

    /**
     * 尝试将参数转到字段类型后再比较，失败时返回 null。
     *
     * @param fieldVal 字段值
     * @param paramVal 参数值
     * @return 转换后的 Comparable，无法比较时返回 null
     */
    private static Comparable compareableOf(Object fieldVal, Object paramVal) {
        if (paramVal == null) {
            return null;
        }
        Object cv = convertToMatch(fieldVal, paramVal);
        if (cv == null || !fieldVal.getClass().isInstance(cv)) {
            return null;
        }
        return (Comparable) cv;
    }

    /**
     * Like
     *
     * @param fieldVal 字段val
     * @param patternVal 模式val
     * @return like的结果
     */
    private static boolean like(Object fieldVal, Object patternVal) {
        if (fieldVal == null || patternVal == null) {
            return false;
        }
        return MemorySqlLex.like(String.valueOf(fieldVal), String.valueOf(patternVal));
    }

    /**
     * 转换转为匹配
     *
     * @param fieldValue 字段值
     * @param paramValue 参数值
     * @return 转换转为匹配的结果
     */
    private static Object convertToMatch(Object fieldValue, Object paramValue) {
        if (fieldValue == null || paramValue == null) {
            return paramValue;
        }
        if (fieldValue.getClass().isInstance(paramValue)) {
            return paramValue;
        }
        if (fieldValue instanceof Number) {
            try {
                String s = String.valueOf(paramValue).trim();
                if (fieldValue instanceof Integer) {
                    return Integer.valueOf(s);
                }
                if (fieldValue instanceof Long) {
                    return Long.valueOf(s);
                }
                if (fieldValue instanceof Double) {
                    return Double.valueOf(s);
                }
                if (fieldValue instanceof Float) {
                    return Float.valueOf(s);
                }
                if (fieldValue instanceof Short) {
                    return Short.valueOf(s);
                }
                if (fieldValue instanceof Byte) {
                    return Byte.valueOf(s);
                }
            } catch (NumberFormatException e) {
                log.debug("[datasource] 参数值数值转换失败，回退后续分支: {}", e.getMessage());
            }
        }
        if (fieldValue instanceof String) {
            return String.valueOf(paramValue);
        }
        return paramValue;
    }

    /**
     * 比较订单
     *
     * @param a a
     * @param b b
     * @param orderBys 订单bys
     * @return compare订单的结果
     * @author CH
     * @since 4.0.0
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> int compareOrdered(T a, T b, List<String> orderBys) {
        for (String ob : orderBys) {
            String[] parts = ob.trim().split("\\s+");
            String field = parts[0];
            boolean desc = parts.length > 1 && "DESC".equalsIgnoreCase(parts[1]);
            Object va = MethodCache.getValue(a, field);
            Object vb = MethodCache.getValue(b, field);
            int cmp;
            if (va == null && vb == null) {
                cmp = 0;
            } else if (va == null) {
                cmp = -1;
            } else if (vb == null) {
                cmp = 1;
            } else if (va instanceof Comparable c1 && vb instanceof Comparable c2) {
                cmp = c1.compareTo(c2);
            } else {
                cmp = va.toString().compareTo(vb.toString());
            }
            if (cmp != 0) {
                return desc ? -cmp : cmp;
            }
        }
        return 0;
    }

    private class InMemQueryWrapper<T> extends EngineQueryWrapper<T> {
        /**
         * In Mem Query Wrapper
         * @param clazz 参数
         * @return 返回值
        */
        InMemQueryWrapper(Class<T> clazz) {
            super(InMemoryEngine.this, clazz);
        }

        /**
         * 列表
        */
        @Override
        public List<T> list() {
            return evaluateQuery(this);
        }

        /**
         * One
        */
        @Override
        public T one() {
            List<T> list = list();
            return list.isEmpty() ? null : list.getFirst();
        }

        /**
         * Page
         *
         * @param pn pn
         * @param ps ps
         * @return page的结果
         * @author CH
         * @since 4.0.0
         */
        @Override
        public Page<T> page(int pn, int ps) {
            return evaluatePage(this, pn, ps);
        }

        /**
         * Count
         * <p>内存引擎无物理 COUNT 可下推，直接复用 {@link #list()} 的内存求值结果计数，
         * 与 {@code page()} 保持同一套过滤语义。</p>
         *
         * @return 命中行数
         */
        @Override
        public long count() {
            return list().size();
        }
    }

    private class InMemUpdateWrapper<T> extends EngineUpdateWrapper<T> {
        /**
         * In Mem Update Wrapper
         * @param clazz 参数
         * @return 返回值
        */
        InMemUpdateWrapper(Class<T> clazz) {
            super(InMemoryEngine.this, clazz);
        }

        /**
         * 更新
         *
         * @return 更新的结果
         * @author CH
         * @since 4.0.0
         */
        @Override
        public int update() {
            return evaluateUpdate(this);
        }
    }

    private class InMemDeleteWrapper<T> extends EngineDeleteWrapper<T> {
        /**
         * In Mem Delete Wrapper
         * @param clazz 参数
         * @return 返回值
        */
        InMemDeleteWrapper(Class<T> clazz) {
            super(InMemoryEngine.this, clazz);
        }

        /**
         * 移除
        */
        @Override
        public int remove() {
            return evaluateDelete(this);
        }
    }
}
