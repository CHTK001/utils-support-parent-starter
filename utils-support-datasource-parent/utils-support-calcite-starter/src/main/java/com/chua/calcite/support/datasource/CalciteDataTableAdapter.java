package com.chua.calcite.support.datasource;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.datasource.support.datasource.DataTable;
import com.chua.datasource.support.datasource.MutableDataTable;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.FilterableTable;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.schema.impl.AbstractTableQueryable;

import com.chua.common.support.utils.CollectionUtils;
import com.chua.common.support.reflection.ReflectUtils;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
* Calcite 数据表适配器，将 {@link DataTable} 适配为 Calcite 的 {@link FilterableTable}。
* <p>
* {@link SourceDataTable}：Engine 条件下推 +（因其继承 MutableDataTable）支持 SQL 写回。<br>
* 其它 {@link MutableDataTable}：modifiabletable 写入。<br>
* 只读表：仅 过滤器table / 全量扫描。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class CalciteDataTableAdapter extends AbstractTable implements FilterableTable {

    /**
    * 被适配的底层 {@link DataTable}
    */
    protected final DataTable dataTable;

    /**
    * 是否启用条件下推（仅 源数据table 启用）
    */
    private final boolean filterable;

    /**
    * 已构建的 Calcite 行类型（懒加载）
    */
    private RelDataType rowType;

    /**
    * 按底层表能力创建适配器。
    * <p>仅可变表声明 {@link ModifiableTable}；并实现可用的 asQueryable，避免 SELECT 走空 Expression。</p>
    * @param dataTable 数据table
    * @return 的的结果
    */
    public static AbstractTable of(DataTable dataTable) {
        if (dataTable instanceof MutableDataTable) {
            return new ModifiableCalciteDataTableAdapter(dataTable);
        }
        return new CalciteDataTableAdapter(dataTable);
    }

    /**
    * 创建 calcite数据table适配器 实例
    * @param dataTable 数据table
    */
    public CalciteDataTableAdapter(DataTable dataTable) {
        this.dataTable = dataTable;
        this.filterable = dataTable instanceof SourceDataTable;
    }

    @Override
    /** 获取row类型 */
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        if (rowType == null) {
            List<String> names = dataTable.getColumnNames();
            List<Class<?>> types;
            if (dataTable instanceof SourceDataTable) {
                types = ((SourceDataTable) dataTable).getColumnTypes();
            } else {
                types = inferColumnTypes(dataTable.getData());
            }
            rowType = buildRowType(typeFactory, names, types);
        }
        return rowType;
    }

    // ---------------------------------------------------------------
 // 过滤器table — 条件下推读取
    // ---------------------------------------------------------------

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
    * 扫描
    *
    * @param root 根
    * @param filters 过滤器
    * @return 扫描的结果
    */
    public Enumerable<Object[]> scan(DataContext root, List<RexNode> filters) {
        if (filterable && CollectionUtils.isNotEmpty(filters)) {
            SourceDataTable source = (SourceDataTable) dataTable;
            log.debug("[calcite] FilterableTable [{}] 收到 {} 个过滤条件, 注入 Engine 原生查询",
                    dataTable.getName(), filters.size());

            try {
                List<String> columnNames = dataTable.getColumnNames();
                List<Condition> conditions = translateRexNodes(filters, columnNames);
                if (CollectionUtils.isNotEmpty(conditions)) {
                    Engine engine = source.getEngine();
                    Class<?> entityClass = source.getEntityClass();
                    LambdaQueryWrapper wrapper = (LambdaQueryWrapper) engine.query(entityClass);
                    for (Condition c : conditions) {
                        wrapper.getConditions().add(c);
                    }
                    List<?> results = wrapper.list();
                    log.debug("[calcite] FilterableTable [{}] 原生下推查询完成: {} 行",
                            dataTable.getName(), results.size());
                    return Linq4j.asEnumerable(toObjectArrays(results, columnNames));
                }
            } catch (Exception e) {
                log.warn("[calcite] FilterableTable [{}] 条件下推失败, 回退全量扫描: {}",
                        dataTable.getName(), e.getMessage());
            }
        }

        return fullScan();
    }

    /**
    * 扫描
    *
    * @param root 根
    * @return 扫描的结果
    */
    public Enumerable<Object[]> scan(DataContext root) {
        return fullScan();
    }

    /**
    * 完整扫描
    *
    * @return 完整扫描的结果
    */
    private Enumerable<Object[]> fullScan() {
        List<Object[]> rows = dataTable.getData().stream()
                .map(this::rowToArray)
                .collect(Collectors.toList());
        log.debug("[calcite] 全量扫描表 [{}] 共 {} 行", dataTable.getName(), rows.size());
        return Linq4j.asEnumerable(rows);
    }

    /**
    * row转为array
    *
    * @param row row
    * @return row转为array的结果
    */
    private Object[] rowToArray(Map<String, Object> row) {
        List<String> names = dataTable.getColumnNames();
        Object[] result = new Object[names.size()];
        for (int i = 0; i < names.size(); i++) {
            result[i] = row.get(names.get(i));
        }
        return result;
    }

    // ---------------------------------------------------------------
 // rex节点 → 条件 翻译
    // ---------------------------------------------------------------

    /**
    * translaterex节点
    *
    * @param filters 过滤器
    * @param columnNames column名称
    * @return translaterex节点的结果
    */
    private List<Condition> translateRexNodes(List<RexNode> filters, List<String> columnNames) {
        List<Condition> result = new ArrayList<>();
        for (RexNode filter : filters) {
            Condition c = translateCondition(filter, columnNames);
            if (c != null) {
                result.add(c);
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
    * translate条件
    *
    * @param node 节点
    * @param columnNames column名称
    * @return translate条件的结果
    */
    private Condition translateCondition(RexNode node, List<String> columnNames) {
        if (node == null) {
            return null;
        }
        SqlKind kind = node.getKind();

        if (kind == SqlKind.AND) {
            RexCall call = (RexCall) node;
            List<Condition> subs = call.getOperands().stream()
                    .map(op -> translateCondition(op, columnNames))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            return subs.isEmpty() ? null : Condition.and(subs);
        }
        if (kind == SqlKind.OR) {
            RexCall call = (RexCall) node;
            List<Condition> subs = call.getOperands().stream()
                    .map(op -> translateCondition(op, columnNames))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            return subs.isEmpty() ? null : Condition.or(subs);
        }
        if (kind == SqlKind.NOT) {
            RexCall call = (RexCall) node;
            if (call.getOperands().size() == 1) {
                Condition inner = translateCondition(call.getOperands().getFirst(), columnNames);
                if (inner != null) {
                    return Condition.of(inner.getColumnName(), negateOp(inner.getOperator()), inner.getValue());
                }
            }
            return null;
        }

        if (kind == SqlKind.IS_NULL || kind == SqlKind.IS_NOT_NULL) {
            RexCall call = (RexCall) node;
            String col = extractColumn(call.getOperands().getFirst(), columnNames);
            return col == null ? null :
                    Condition.of(col, kind == SqlKind.IS_NULL ? "IS NULL" : "IS NOT NULL", null);
        }

        String op = sqlKindToOperator(kind);
        if (op == null) {
            return null;
        }

        RexCall call = (RexCall) node;
        List<RexNode> operands = call.getOperands();
        if (operands.size() < 2) {
            return null;
        }

        RexNode left = operands.getFirst();
        RexNode right = operands.get(1);

        String colName;
        Object value;

        if (left instanceof RexInputRef && !(right instanceof RexInputRef)) {
            colName = extractColumn(left, columnNames);
            value = extractValue(right);
        } else if (right instanceof RexInputRef && !(left instanceof RexInputRef)) {
            colName = extractColumn(right, columnNames);
            value = extractValue(left);
            op = flipOp(op);
        } else {
            return null;
        }

        if (colName == null) {
            return null;
        }
        return Condition.of(colName, op, value);
    }

    /**
    * extractcolumn
    *
    * @param node 节点
    * @param columnNames column名称
    * @return extractColumn的结果
    */
    private String extractColumn(RexNode node, List<String> columnNames) {
        if (node instanceof RexInputRef) {
            int idx = ((RexInputRef) node).getIndex();
            if (idx >= 0 && idx < columnNames.size()) {
                return columnNames.get(idx);
            }
        }
        return null;
    }

    /**
    * extract值
    *
    * @param node 节点
    * @return extract值的结果
    */
    private Object extractValue(RexNode node) {
        if (node instanceof RexLiteral) {
            RexLiteral literal = (RexLiteral) node;
            if (literal.isNull()) {
                return null;
            }
            switch (literal.getTypeName()) {
                case BOOLEAN:
                    return RexLiteral.booleanValue(literal);
                case TINYINT:
                case SMALLINT:
                case INTEGER:
                    return RexLiteral.intValue(literal);
                case BIGINT:
                    return RexLiteral.longValue(literal);
                case FLOAT:
                case DOUBLE:
                case REAL:
                    return ((BigDecimal) literal.getValue()).doubleValue();
                case DECIMAL:
                    return literal.getValueAs(BigDecimal.class);
                case CHAR:
                case VARCHAR:
                    return RexLiteral.stringValue(literal);
                default:
                    return literal.getValue();
            }
        }
        return null;
    }

    /**
    * sql种类转为操作符
    *
    * @param kind 种类
    * @return sql种类转为操作符的结果
    */
    private static String sqlKindToOperator(SqlKind kind) {
        switch (kind) {
            case EQUALS:
                return "=";
            case NOT_EQUALS:
                return "!=";
            case GREATER_THAN:
                return ">";
            case GREATER_THAN_OR_EQUAL:
                return ">=";
            case LESS_THAN:
                return "<";
            case LESS_THAN_OR_EQUAL:
                return "<=";
            case LIKE:
                return "LIKE";
            case IN:
                return "IN";
            case NOT_IN:
                return "NOT IN";
            case BETWEEN:
                return "BETWEEN";
            default:
                return null;
        }
    }

    /**
    * flipop
    *
    * @param op op
    * @return flipOp的结果
    */
    private static String flipOp(String op) {
        if (">".equals(op)) {
            return "<";
        }
        if ("<".equals(op)) {
            return ">";
        }
        if (">=".equals(op)) {
            return "<=";
        }
        if ("<=".equals(op)) {
            return ">=";
        }
        return op;
    }

    /**
    * negateop
    *
    * @param op op
    * @return negateOp的结果
    */
    private static String negateOp(String op) {
        if ("=".equals(op)) {
            return "!=";
        }
        if ("!=".equals(op)) {
            return "=";
        }
        if (">".equals(op)) {
            return "<=";
        }
        if (">=".equals(op)) {
            return "<";
        }
        if ("<".equals(op)) {
            return ">=";
        }
        if ("<=".equals(op)) {
            return ">";
        }
        if ("LIKE".equals(op)) {
            return "NOT LIKE";
        }
        if ("IN".equals(op)) {
            return "NOT IN";
        }
        if ("IS NULL".equals(op)) {
            return "IS NOT NULL";
        }
        if ("IS NOT NULL".equals(op)) {
            return "IS NULL";
        }
        return op;
    }

    // ---------------------------------------------------------------
 // 实体 → 对象[] 转换
    // ---------------------------------------------------------------

    /**
    * 转为对象arrays
    *
    * @param entities 实体
    * @param columnNames column名称
    * @return 转为对象arrays的结果
    */
    private static List<Object[]> toObjectArrays(List<?> entities, List<String> columnNames) {
        if (CollectionUtils.isEmpty(entities)) {
            return Collections.emptyList();
        }
        List<Method> getters = resolveGetters(entities.getFirst().getClass());
        Map<String, Method> getterMap = toGetterMap(getters);
        List<Object[]> result = new ArrayList<>(entities.size());
        for (Object entity : entities) {
            Object[] row = new Object[columnNames.size()];
            for (int i = 0; i < columnNames.size(); i++) {
                Method getter = getterMap.get(columnNames.get(i));
                if (getter != null) {
                    try {
                        row[i] = ReflectUtils.invoke(entity, getter.getName(), Object.class);
                    } catch (Exception ignored) {
                        row[i] = null;
                    }
                } else {
                    row[i] = null;
                }
            }
            result.add(row);
        }
        return result;
    }

    /**
    * 解析Getters
    *
    * @param entityClass 实体类
    * @return resolveGetters的结果
    */
    private static List<Method> resolveGetters(Class<?> entityClass) {
        List<Method> result = new ArrayList<>();
        for (Method method : entityClass.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() != 0) {
                continue;
            }
            if (method.getReturnType() == void.class) {
                continue;
            }
            String methodName = method.getName();
            if (methodName.equals("getClass")) {
                continue;
            }
            if ((methodName.startsWith("get") && methodName.length() > 3)
                    || (methodName.startsWith("is") && methodName.length() > 2)) {
                result.add(method);
            }
        }
        return result;
    }

    /**
    * 转为getter映射
    *
    * @param getters getters
    * @return 转为getter映射的结果
    */
    private static Map<String, Method> toGetterMap(List<Method> getters) {
        Map<String, Method> map = new LinkedHashMap<>();
        for (Method getter : getters) {
            map.put(getterToColumnName(getter), getter);
        }
        return map;
    }

    /**
    * getter转为column名称
    *
    * @param getter getter
    * @return getter转为column名称的结果
    */
    private static String getterToColumnName(Method getter) {
        String methodName = getter.getName();
        String prop = methodName.startsWith("is") ? methodName.substring(2) : methodName.substring(3);
        if (prop.isEmpty()) {
            return methodName;
        }
        return Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
    }

    // ---------------------------------------------------------------
 // 可变表适配器 — 仅 mutable数据table 声明 modifiabletable
    // ---------------------------------------------------------------

    private static final class ModifiableCalciteDataTableAdapter
            extends CalciteDataTableAdapter implements ModifiableTable {

        ModifiableCalciteDataTableAdapter(DataTable dataTable) {
            super(dataTable);
        }

        @Override
        /** 获取modifiable集合 */
        public Collection getModifiableCollection() {
            return new ObjectArrayMutableCollection(dataTable);
        }

        @Override
        /**
        * 转为修改rel
        * @param cluster cluster
        * @param table table
        * @param catalogReader catalog读取
        * @param child 子
        * @param operation operation
        * @param updateColumnList 更新column列表
        * @param sourceExpressionList 源expression列表
        * @param flattened flattened
        * @param catalogReader catalog读取
        * @param child 子
        * @param operation operation
        * @param updateColumnList 更新column列表
        * @param sourceExpressionList 源expression列表
        * @param flattened flattened
        * @param queryProvider 查询提供者
        * @param schema 模式
        * @param tableName table名称
        * @param schema 模式
        * @param this this
        * @param tableName table名称
        * @param schema 模式
        * @param tableName table名称
        * @param clazz clazz
        * @param tableName table名称
        * @param clazz clazz
        * @param dataTable 数据table
        * @param index 索引
        * @param element element
        * @param index 索引
        * @param element element
        * @param index 索引
        * @param o o
        * @param c c
        * @param index 索引
        * @param element element
        * @param element element
        * @param row row
        * @param arr arr
        * @param v v
        * @param source 源
        * @param mutable mutable
        * @param values 值
        * @param o o
        * @param other other
        * @param typeFactory 类型工厂
        * @param names 名称
        * @param types 类型
        * @param typeFactory 类型工厂
        * @param names 名称
        * @param clazz clazz
        * @param typeFactory 类型工厂
        * @param true true
        * @param data 数据
        */
        public TableModify toModificationRel(
                RelOptCluster cluster,
                RelOptTable table,
                Prepare.CatalogReader catalogReader,
                RelNode child,
                TableModify.Operation operation,
                @Nullable List<String> updateColumnList,
                @Nullable List<RexNode> sourceExpressionList,
                boolean flattened) {
            return LogicalTableModify.create(
                    table, catalogReader, child, operation,
                    updateColumnList, sourceExpressionList, flattened);
        }

        @Override
        @SuppressWarnings("unchecked")
        /**
        * as查询
        *
        * @param queryProvider 查询提供者
        * @param schema 模式
        * @param tableName table名称
        * @return as查询的结果
        */
        public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema, String tableName) {
            final DataTable source = this.dataTable;
            return new AbstractTableQueryable<T>(queryProvider, schema, this, tableName) {
                @Override
                /** Enumerator */
                public Enumerator<T> enumerator() {
                    List<String> names = source.getColumnNames();
                    List<Object[]> rows = new ArrayList<>();
                    for (Map<String, Object> row : source.getData()) {
                        Object[] arr = new Object[names.size()];
                        for (int i = 0; i < names.size(); i++) {
                            arr[i] = row.get(names.get(i));
                        }
                        rows.add(arr);
                    }
                    return (Enumerator<T>) Linq4j.enumerator(rows);
                }
            };
        }

        @Override
        /** 获取element类型 */
        public Type getElementType() {
            return Object[].class;
        }

        @Override
        /** 获取Expression */
        public Expression getExpression(SchemaPlus schema, String tableName, Class clazz) {
 // 第 4 参必须是调用方传入的 clazz（查询/Enumerable），不能写死 对象[]
            return Schemas.tableExpression(schema, getElementType(), tableName, clazz);
        }
    }

    /**
                * Calcite DML 以 对象[] 行为元素；变更后同步回 mutable数据table / 源数据table。
                * <p>元素使用 {@link Row}（内容等值），保证 DELETE 的 removeAll 能匹配成功。</p>
                * <p>Calcite ModifiableTable 仅支持 INSERT / DELETE，不支持 SQL UPDATE。</p>
                * @author CH
                * @since 4.0.0
                */
    private static class ObjectArrayMutableCollection extends AbstractList<Object> {

        /** 数据表 */
        private final DataTable dataTable;
        /** 列名称 */
        private final List<String> columnNames;
        /** Rows */
        private final List<Row> rows = new ArrayList<>();

        ObjectArrayMutableCollection(DataTable dataTable) {
            this.dataTable = dataTable;
            this.columnNames = dataTable.getColumnNames();
            for (Map<String, Object> row : dataTable.getData()) {
                Object[] arr = new Object[columnNames.size()];
                for (int i = 0; i < columnNames.size(); i++) {
                    arr[i] = row.get(columnNames.get(i));
                }
                rows.add(new Row(arr));
            }
        }

        @Override
        /** 获取 */
        public Object get(int index) {
            return rows.get(index).values;
        }

        @Override
        /** 获取大小 */
        public int size() {
            return rows.size();
        }

        @Override
        /** 添加 */
        public boolean add(Object element) {
            rows.add(toRow(element));
            flush();
            return true;
        }

        @Override
        /** 添加 */
        public void add(int index, Object element) {
            rows.add(index, toRow(element));
            flush();
        }

        @Override
        /** 移除 */
        public Object remove(int index) {
            Row removed = rows.remove(index);
            flush();
            return removed.values;
        }

        @Override
        /** 移除 */
        public boolean remove(Object o) {
            boolean ok = rows.remove(toRow(o));
            if (ok) {
                flush();
            }
            return ok;
        }

        @Override
        /** 移除全部 */
        public boolean removeAll(Collection<?> c) {
            boolean changed = false;
            for (Object o : c) {
                if (rows.remove(toRow(o))) {
                    changed = true;
                }
            }
            if (changed) {
                flush();
            }
            return changed;
        }

        @Override
        /** 设置 */
        public Object set(int index, Object element) {
            Row old = rows.set(index, toRow(element));
            flush();
            return old.values;
        }

        @Override
        /** Clear */
        public void clear() {
            rows.clear();
            flush();
        }

        /**
        * 转为row
        *
        * @param element element
        * @return 转为row的结果
        */
        private static Row toRow(Object element) {
            if (element instanceof Row row) {
                return row;
            }
            if (element instanceof Object[] arr) {
                return new Row(arr);
            }
            return new Row(new Object[]{element});
        }

        /** 刷新 */
        private void flush() {
            List<Map<String, Object>> maps = new ArrayList<>(rows.size());
            for (Row row : rows) {
                Object[] arr = row.values;
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < columnNames.size(); i++) {
                    Object v = (arr != null && i < arr.length) ? arr[i] : null;
                    map.put(columnNames.get(i), v);
                }
                maps.add(map);
            }
            if (dataTable instanceof SourceDataTable source) {
                source.replaceAllRows(maps);
            } else if (dataTable instanceof MutableDataTable mutable) {
                List<Map<String, Object>> data = mutable.getData();
                data.clear();
                data.addAll(maps);
            }
        }
    }

    /** 内容等值的行包装，供 移除全部 匹配。 */
    private static final class Row {
        final Object[] values;

        Row(Object[] values) {
            this.values = values != null ? values : new Object[0];
        }

        @Override
        /** 判断相等 */
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Row other)) {
                return false;
            }
            return Arrays.equals(values, other.values);
        }

        @Override
        /** 哈希编码 */
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    // ---------------------------------------------------------------
    // 类型工具
    // ---------------------------------------------------------------

    /**
        * 构建row类型
        * @param typeFactory 类型工厂
        * @param names 名称
        * @param types 类型
        */
    static RelDataType buildRowType(RelDataTypeFactory typeFactory,
                                     List<String> names, List<Class<?>> types) {
        List<RelDataType> sqlTypes = types.stream()
                .map(t -> javaClassToSqlType(t, typeFactory))
                .collect(Collectors.toList());
        return typeFactory.createStructType(sqlTypes, names);
    }

    /**
    * java类转为sql类型
    *
    * @param clazz clazz
    * @param typeFactory 类型工厂
    * @return java类转为sql类型的结果
    */
    static RelDataType javaClassToSqlType(Class<?> clazz, RelDataTypeFactory typeFactory) {
        SqlTypeName sqlTypeName;
        if (clazz == String.class) {
            sqlTypeName = SqlTypeName.VARCHAR;
        } else if (clazz == Integer.class || clazz == int.class) {
            sqlTypeName = SqlTypeName.INTEGER;
        } else if (clazz == Long.class || clazz == long.class) {
            sqlTypeName = SqlTypeName.BIGINT;
        } else if (clazz == Short.class || clazz == short.class) {
            sqlTypeName = SqlTypeName.SMALLINT;
        } else if (clazz == Byte.class || clazz == byte.class) {
            sqlTypeName = SqlTypeName.TINYINT;
        } else if (clazz == Double.class || clazz == double.class) {
            sqlTypeName = SqlTypeName.DOUBLE;
        } else if (clazz == Float.class || clazz == float.class) {
            sqlTypeName = SqlTypeName.FLOAT;
        } else if (clazz == Boolean.class || clazz == boolean.class) {
            sqlTypeName = SqlTypeName.BOOLEAN;
        } else if (clazz == java.math.BigDecimal.class) {
            sqlTypeName = SqlTypeName.DECIMAL;
        } else if (clazz == java.sql.Date.class) {
            sqlTypeName = SqlTypeName.DATE;
        } else if (clazz == java.sql.Time.class) {
            sqlTypeName = SqlTypeName.TIME;
        } else if (clazz == java.sql.Timestamp.class || clazz == java.util.Date.class) {
            sqlTypeName = SqlTypeName.TIMESTAMP;
        } else if (clazz == byte[].class) {
            sqlTypeName = SqlTypeName.VARBINARY;
        } else {
            sqlTypeName = SqlTypeName.VARCHAR;
        }
        return typeFactory.createTypeWithNullability(typeFactory.createSqlType(sqlTypeName), true);
    }

    /**
    * 从已有行数据推断各列类型。
    * @param data 数据
    * @return infercolumn类型的结果
    */
    private static List<Class<?>> inferColumnTypes(List<Map<String, Object>> data) {
        if (CollectionUtils.isEmpty(data)) {
            return Collections.emptyList();
        }
        Map<String, Object> first = data.getFirst();
        List<Class<?>> types = new ArrayList<>(first.size());
        for (Map.Entry<String, Object> entry : first.entrySet()) {
            if (entry.getValue() != null) {
                types.add(entry.getValue().getClass());
            } else {
                types.add(Object.class);
            }
        }
        return types;
    }
}
