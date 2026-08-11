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
 * 其它 {@link MutableDataTable}：ModifiableTable 写入。<br>
 * 只读表：仅 FilterableTable / 全量扫描。
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
     * 是否启用条件下推（仅 SourceDataTable 启用）
     */
    private final boolean filterable;

    /**
     * 已构建的 Calcite 行类型（懒加载）
     */
    private RelDataType rowType;

    /**
     * 按底层表能力创建适配器。
     * <p>仅可变表声明 {@link ModifiableTable}；并实现可用的 asQueryable，避免 SELECT 走空 Expression。</p>
     */
    public static AbstractTable of(DataTable dataTable) {
        if (dataTable instanceof MutableDataTable) {
            return new ModifiableCalciteDataTableAdapter(dataTable);
        }
        return new CalciteDataTableAdapter(dataTable);
    }

    public CalciteDataTableAdapter(DataTable dataTable) {
        this.dataTable = dataTable;
        this.filterable = dataTable instanceof SourceDataTable;
    }

    @Override
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
    // FilterableTable — 条件下推读取
    // ---------------------------------------------------------------

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
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

    public Enumerable<Object[]> scan(DataContext root) {
        return fullScan();
    }

    private Enumerable<Object[]> fullScan() {
        List<Object[]> rows = dataTable.getData().stream()
                .map(this::rowToArray)
                .collect(Collectors.toList());
        log.debug("[calcite] 全量扫描表 [{}] 共 {} 行", dataTable.getName(), rows.size());
        return Linq4j.asEnumerable(rows);
    }

    private Object[] rowToArray(Map<String, Object> row) {
        List<String> names = dataTable.getColumnNames();
        Object[] result = new Object[names.size()];
        for (int i = 0; i < names.size(); i++) {
            result[i] = row.get(names.get(i));
        }
        return result;
    }

    // ---------------------------------------------------------------
    // RexNode → Condition 翻译
    // ---------------------------------------------------------------

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
                Condition inner = translateCondition(call.getOperands().get(0), columnNames);
                if (inner != null) {
                    return Condition.of(inner.getColumnName(), negateOp(inner.getOperator()), inner.getValue());
                }
            }
            return null;
        }

        if (kind == SqlKind.IS_NULL || kind == SqlKind.IS_NOT_NULL) {
            RexCall call = (RexCall) node;
            String col = extractColumn(call.getOperands().get(0), columnNames);
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

        RexNode left = operands.get(0);
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

    private String extractColumn(RexNode node, List<String> columnNames) {
        if (node instanceof RexInputRef) {
            int idx = ((RexInputRef) node).getIndex();
            if (idx >= 0 && idx < columnNames.size()) {
                return columnNames.get(idx);
            }
        }
        return null;
    }

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
    // 实体 → Object[] 转换
    // ---------------------------------------------------------------

    private static List<Object[]> toObjectArrays(List<?> entities, List<String> columnNames) {
        if (CollectionUtils.isEmpty(entities)) {
            return Collections.emptyList();
        }
        List<Method> getters = resolveGetters(entities.get(0).getClass());
        Map<String, Method> getterMap = toGetterMap(getters);
        List<Object[]> result = new ArrayList<>(entities.size());
        for (Object entity : entities) {
            Object[] row = new Object[columnNames.size()];
            for (int i = 0; i < columnNames.size(); i++) {
                Method getter = getterMap.get(columnNames.get(i));
                if (getter != null) {
                    try {
                        row[i] = getter.invoke(entity);
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

    private static Map<String, Method> toGetterMap(List<Method> getters) {
        Map<String, Method> map = new LinkedHashMap<>();
        for (Method getter : getters) {
            map.put(getterToColumnName(getter), getter);
        }
        return map;
    }

    private static String getterToColumnName(Method getter) {
        String methodName = getter.getName();
        String prop = methodName.startsWith("is") ? methodName.substring(2) : methodName.substring(3);
        if (prop.isEmpty()) {
            return methodName;
        }
        return Character.toLowerCase(prop.charAt(0)) + prop.substring(1);
    }

    // ---------------------------------------------------------------
    // 可变表适配器 — 仅 MutableDataTable 声明 ModifiableTable
    // ---------------------------------------------------------------

    private static final class ModifiableCalciteDataTableAdapter
            extends CalciteDataTableAdapter implements ModifiableTable {

        ModifiableCalciteDataTableAdapter(DataTable dataTable) {
            super(dataTable);
        }

        @Override
        public Collection getModifiableCollection() {
            return new ObjectArrayMutableCollection(dataTable);
        }

        @Override
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
        public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema, String tableName) {
            final DataTable source = this.dataTable;
            return new AbstractTableQueryable<T>(queryProvider, schema, this, tableName) {
                @Override
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
        public Type getElementType() {
            return Object[].class;
        }

        @Override
        public Expression getExpression(SchemaPlus schema, String tableName, Class clazz) {
            // 第 4 参必须是调用方传入的 clazz（Queryable/Enumerable），不能写死 Object[]
            return Schemas.tableExpression(schema, getElementType(), tableName, clazz);
        }
    }

    /**
     * Calcite DML 以 Object[] 行为元素；变更后同步回 MutableDataTable / SourceDataTable。
     * <p>元素使用 {@link Row}（内容等值），保证 DELETE 的 removeAll 能匹配成功。</p>
     * <p>Calcite ModifiableTable 仅支持 INSERT / DELETE，不支持 SQL UPDATE。</p>
     */
    private static class ObjectArrayMutableCollection extends AbstractList<Object> {

        private final DataTable dataTable;
        private final List<String> columnNames;
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
        public Object get(int index) {
            return rows.get(index).values;
        }

        @Override
        public int size() {
            return rows.size();
        }

        @Override
        public boolean add(Object element) {
            rows.add(toRow(element));
            flush();
            return true;
        }

        @Override
        public void add(int index, Object element) {
            rows.add(index, toRow(element));
            flush();
        }

        @Override
        public Object remove(int index) {
            Row removed = rows.remove(index);
            flush();
            return removed.values;
        }

        @Override
        public boolean remove(Object o) {
            boolean ok = rows.remove(toRow(o));
            if (ok) {
                flush();
            }
            return ok;
        }

        @Override
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
        public Object set(int index, Object element) {
            Row old = rows.set(index, toRow(element));
            flush();
            return old.values;
        }

        @Override
        public void clear() {
            rows.clear();
            flush();
        }

        private static Row toRow(Object element) {
            if (element instanceof Row row) {
                return row;
            }
            if (element instanceof Object[] arr) {
                return new Row(arr);
            }
            return new Row(new Object[]{element});
        }

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

    /** 内容等值的行包装，供 removeAll 匹配。 */
    private static final class Row {
        final Object[] values;

        Row(Object[] values) {
            this.values = values != null ? values : new Object[0];
        }

        @Override
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
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    // ---------------------------------------------------------------
    // 类型工具
    // ---------------------------------------------------------------

    static RelDataType buildRowType(RelDataTypeFactory typeFactory,
                                     List<String> names, List<Class<?>> types) {
        List<RelDataType> sqlTypes = types.stream()
                .map(t -> javaClassToSqlType(t, typeFactory))
                .collect(Collectors.toList());
        return typeFactory.createStructType(sqlTypes, names);
    }

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
     */
    private static List<Class<?>> inferColumnTypes(List<Map<String, Object>> data) {
        if (CollectionUtils.isEmpty(data)) {
            return Collections.emptyList();
        }
        Map<String, Object> first = data.get(0);
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
