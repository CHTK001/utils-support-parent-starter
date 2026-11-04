package com.chua.common.support.apm.orm;

import com.chua.common.support.apm.OtnlEngine;
import com.chua.common.support.lang.datasource.engine.wrapper.Condition;
import com.chua.common.support.lang.datasource.engine.wrapper.LambdaQueryWrapper;
import com.chua.common.support.lang.datasource.page.Page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Otnl 引擎的 Lambda 查询包装器（ORM 通道执行端）。
 * <p>
 * 链式条件、排序、分页完全复用 {@link LambdaQueryWrapper} 的构建逻辑 ——
 * {@code eq/ne/gt/ge/lt/le/like/in/between/isNull/and/or/orderBy/limit/offset}
 * 一个不少，解析出的列名也与数据库场景一致（驼峰转下划线）。
 * </p>
 * <p>
 * 唯一的差别在终端执行：本类不拼 SQL，而是向
 * {@link OtnlEngine#dataset(Class)} 取实体行，再用 {@link OtnlOrmSupport}
 * 在内存里过滤、排序、切页。这样「可观测数据」与「数据库表」对业务侧是同一种写法：
 * </p>
 * <pre>{@code
 * Page<OtnlAlarm> page = engine.query(OtnlAlarm.class)
 *         .like(OtnlAlarm::message, "timeout")
 *         .orderByDesc(OtnlAlarm::startTime)
 *         .page(1, 20);
 * }</pre>
 *
 * @param <T> 实体类型
 * @author CH
 * @since 4.0.0.51
 */
public class OtnlLambdaQueryWrapper<T> extends LambdaQueryWrapper<T> {

    private final OtnlEngine engine;

    /**
     * 创建包装器
     *
     * @param engine      引擎
     * @param entityClass 实体类型
     */
    public OtnlLambdaQueryWrapper(OtnlEngine engine, Class<T> entityClass) {
        super(entityClass);
        this.engine = engine;
    }

    /**
     * 执行查询，返回实体列表
     *
     * @return 实体列表
     */
    @Override
    public List<T> list() {
        return slice(select());
    }

    /**
     * 执行查询，返回第一条
     *
     * @return 实体，无数据返回 null
     */
    @Override
    public T one() {
        List<T> rows = select();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 分页查询
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @Override
    public Page<T> page(int pageNum, int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, pageSize);
        List<T> matched = select();
        long total = matched.size();
        int from = Math.min((safePage - 1) * safeSize, matched.size());
        int to = Math.min(from + safeSize, matched.size());
        return new Page<>(safePage, safeSize, total, new ArrayList<>(matched.subList(from, to)));
    }

    /**
     * 统计命中行数
     *
     * @return 行数
     */
    @Override
    public long count() {
        return select().size();
    }

    /**
     * 过滤 + 排序后的全量命中行（不含分页上限）
     *
     * @return 命中行
     */
    private List<T> select() {
        List<T> rows = engine == null ? List.of() : engine.dataset(getEntityClass());
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Condition> conditions = getConditions();
        List<T> matched = new ArrayList<>();
        for (T row : rows) {
            if (OtnlOrmSupport.matches(row, conditions)) {
                matched.add(row);
            }
        }
        sort(matched);
        return matched;
    }

    /**
     * 按链式 orderBy 排序（稳定排序，多个键按书写顺序逐级比较）
     *
     * @param rows 待排序行
     */
    private void sort(List<T> rows) {
        List<String> orderBys = getOrderBys();
        if (orderBys == null || orderBys.isEmpty() || rows.size() < 2) {
            return;
        }
        List<OtnlOrmSupport.OrderKey> keys = new ArrayList<>(orderBys.size());
        for (String orderBy : orderBys) {
            OtnlOrmSupport.OrderKey key = OtnlOrmSupport.orderKey(orderBy);
            if (!key.column().isBlank()) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        Comparator<T> comparator = (left, right) -> {
            for (OtnlOrmSupport.OrderKey key : keys) {
                Object a = OtnlOrmSupport.value(left, key.column());
                Object b = OtnlOrmSupport.value(right, key.column());
                int result = compareValues(a, b);
                if (result != 0) {
                    return key.desc() ? -result : result;
                }
            }
            return 0;
        };
        rows.sort(comparator);
    }

    /**
     * 排序比较：null 恒排最后，数值按大小，其余按文本
     *
     * @param left  左值
     * @param right 右值
     * @return 比较结果
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    /**
     * 应用 limit / offset
     *
     * @param rows 全量命中行
     * @return 切片后的行
     */
    private List<T> slice(List<T> rows) {
        int offset = Math.max(0, getOffset());
        int limit = getLimit();
        if (offset == 0 && limit <= 0) {
            return rows;
        }
        int from = Math.min(offset, rows.size());
        int to = limit > 0 ? Math.min(from + limit, rows.size()) : rows.size();
        return new ArrayList<>(rows.subList(from, to));
    }
}
