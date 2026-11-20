package com.chua.datasource.support.engine;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.reflection.ReflectUtils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.chua.common.support.utils.StringUtils;

/**
 * 结果集列名 → 实体字段的绑定规则。
 *
 * <p>这是 JDBC / R2DBC 两条链路唯一的字段写入入口，集中在此的原因是一次真实缺陷：
 * {@link JdbcReactorEngine} 早期只按「列名原样 + 下划线转驼峰」做精确查找，
 * 而 H2/PostgreSQL/Oracle 默认返回 <b>全大写</b>列标签（{@code ID}/{@code NAME}），
 * 精确查找必然失败——表现为「查询成功但实体字段全是 null」。
 * 两处各写一份映射逻辑时，这种修正只会落到其中一条链路上。</p>
 *
 * <p>查找顺序（每一层都遍历父类，命中即返回）：</p>
 * <ol>
 *   <li>列名原样 —— 覆盖 MySQL 返回小写标签的场景</li>
 *   <li>全小写 —— 覆盖 H2/PostgreSQL/Oracle 大写标签</li>
 *   <li>下划线转驼峰 —— {@code user_name} → {@code userName}</li>
 *   <li>忽略大小写与下划线的宽松匹配 —— {@code DEPT_ID} → {@code deptId}</li>
 * </ol>
 *
 * <p><b>性能口径</b>：本方法在结果集逐行逐列的循环里执行，属于最热路径。
 * 早期实现每个单元格都要构造候选名数组、做大小写/驼峰/去下划线归一化，
 * 并沿继承链用「抛 {@code NoSuchFieldException}」的方式试探字段，
 * 宽松匹配还要克隆整张 {@code getDeclaredFields()} 表——一次万行查询
 * 可能产生百万级临时对象与异常。现改为按「实体类 + 列标签」缓存解析结果：
 * 同一结果集的列标签逐行重复，首次解析后每格只剩一次哈希查找与一次字段写入；
 * 无对应字段的辅助列（如 {@code ROW_NUM}）以哨兵做负缓存，不再反复解析。
 * 缓存键对类用软引用持有，类随类加载器回收后缓存项可被 JVM 自然释放。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
final class EntityFieldMapper {

    /**
     * 「该列名没有对应字段」的负缓存哨兵：区分「尚未解析」与「解析过、确无字段」，
     * 避免辅助列每个单元格都重走一遍四层查找。
     */
    private static final Object MISSING = new Object();

    /**
     * 绑定缓存：实体类 → (结果集列标签 → 字段或 {@link #MISSING})。
     *
     * <p>类键用软引用持有：运行中实体类不会被回收，类加载器整体回收时缓存项随之可释放，
     * 不造成类加载器泄漏。</p>
     */
    private static final Map<Class<?>, Map<String, Object>> BINDINGS = new ConcurrentReferenceHashMap<>();

    /**
     * 工具类，禁止实例化。
     */
    private EntityFieldMapper() {
    }

    /**
     * 把结果集的一列写入实体属性。
     *
     * <p>列名匹配不到任何字段时静默跳过：结果集可能带有 {@code ROW_NUM} 之类的辅助列，
     * 实体没有对应属性是正常情况，不应让整条查询失败。
     * 基本类型字段遇到 {@code null} 时直接跳过，避免拆箱 NPE。</p>
     *
     * @param instance   目标实体实例
     * @param columnName 结果集列标签
     * @param value      列值，可为 null
     */
    static void setFieldValue(Object instance, String columnName, Object value) {
        if (instance == null || StringUtils.isEmpty(columnName)) {
            return;
        }
        Field field = bindingOf(instance.getClass(), columnName);
        if (field == null) {
            return;
        }
        if (field.getType().isPrimitive() && value == null) {
            return;
        }
        // 复用转换器 完成类型转换（如 Oracle NUMBER → BigDecimal 赋给 Long 字段）
        Object converted = Converter.convertIfNecessary(value, field.getType());
        if (converted != null) {
            ReflectUtils.setField(instance, field.getName(), converted);
        }
    }

    /**
     * 取「实体类 + 列标签」的绑定字段：优先命中缓存，未命中时按四层规则解析一次并回填。
     *
     * @param entityClass 实体类
     * @param columnName  结果集列标签
     * @return 匹配字段，无对应字段返回 null
     */
    private static Field bindingOf(Class<?> entityClass, String columnName) {
        Map<String, Object> bindings = BINDINGS.computeIfAbsent(entityClass, k -> new ConcurrentHashMap<>(32));
        Object cached = bindings.get(columnName);
        if (cached != null) {
            return cached == MISSING ? null : (Field) cached;
        }
        Field resolved = resolveField(entityClass, columnName);
        // 并发下重复解析结果确定，put 互相覆盖无副作用
        bindings.put(columnName, resolved == null ? MISSING : resolved);
        return resolved;
    }

    /**
     * 按四层规则解析列名对应的字段（仅缓存未命中时调用）。
     *
     * @param entityClass 实体类
     * @param columnName  结果集列标签
     * @return 匹配字段，无对应字段返回 null
     */
    private static Field resolveField(Class<?> entityClass, String columnName) {
        String[] candidates = {
                columnName,
                columnName.toLowerCase(),
                toCamelCase(columnName)
        };
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            for (String candidate : candidates) {
                Field field = ReflectUtils.findField(current, candidate);
                if (field != null) {
                    return field;
                }
            }
            // 兜底：忽略大小写与下划线的宽松匹配，覆盖 H2/Oracle 大写蛇形标签
            // （如 DEPT_ID → 字段 deptId、TOTAL_SALARY → totalSalary）
            Field loose = findFieldLoose(current, columnName);
            if (loose != null) {
                return loose;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 按忽略大小写与下划线的规则宽松查找字段。
     *
     * @param type       字段所在的类
     * @param columnName 结果集列名
     * @return 匹配的字段，未匹配返回 null
     */
    private static Field findFieldLoose(Class<?> type, String columnName) {
        String normalizedColumn = columnName.replace("_", "").toLowerCase();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            String normalizedField = field.getName().replace("_", "").toLowerCase();
            if (normalizedField.equals(normalizedColumn)) {
                return field;
            }
        }
        return null;
    }

    /**
     * 下划线列名转驼峰属性名（如 {@code user_name} → {@code userName}）。
     *
     * @param name 原始列名
     * @return 驼峰属性名
     */
    static String toCamelCase(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        boolean upperNext = false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(ch));
                upperNext = false;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
