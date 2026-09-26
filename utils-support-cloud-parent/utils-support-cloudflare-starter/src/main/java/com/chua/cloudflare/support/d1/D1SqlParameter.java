package com.chua.cloudflare.support.d1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D1 SQL 参数绑定（同时支持 ? 占位符与 :名称 命名参数）。
 *
 * @author CH
 * @since 4.0.0.42
 * @param positional 位置
 * @param named 名称
 * @return d1sql参数的结果
 */
public record D1SqlParameter(Object[] positional, Map<String, Object> named) {

    /**
     * 命名参数占位符匹配（{@code :name}）
     */
    private static final Pattern NAMED_PARAM = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    /**
     * 规范构造器：数组与映射均做防御性拷贝。
     *
     * <p>value class 前置条件——数组与集合组件必须深不可变。此处保留元素可空性，
     * 因此使用 unmodifiable 包装而非 {@code copyOf}。</p>
     *
     * @param positional 位置参数数组
     * @param named 命名参数映射
     */
    public D1SqlParameter {
        positional = Objects.requireNonNull(positional, "positional 不能为 null").clone();
        named = Collections.unmodifiableMap(
                new HashMap<>(Objects.requireNonNull(named, "named 不能为 null")));
    }

    /**
     * 位置参数访问器：返回内部数组的副本，避免调用方改写内部状态。
     *
     * @return 位置参数数组副本
     */
    @Override
    public Object[] positional() {
        return positional.clone();
    }

    /**
     * 构造位置参数。
     *
     * @param params 参数列表（可空/可 空）
     * @return D1SqlParameter
     */
    public static D1SqlParameter ofPositional(Object[] params) {
        return new D1SqlParameter(params == null ? new Object[0] : params, Map.of());
    }

    /**
     * 构造命名参数。
     *
     * @param named 参数映射
     * @return D1SqlParameter
     */
    public static D1SqlParameter ofNamed(Map<String, Object> named) {
        return new D1SqlParameter(new Object[0], named == null ? Map.of() : new HashMap<>(named));
    }

    /**
     * 获取位置参数（{@code ?} 占位符）。
     *
     * @return 位置参数数组（不为 空）
     */
    public Object[] getPositional() {
        return positional == null ? new Object[0] : positional;
    }

    /**
     * 获取命名参数映射。
     *
     * @return 命名参数 映射
     */
    public Map<String, Object> getNamed() {
        return named == null ? Map.of() : named;
    }

    /**
     * 序列化为 D1 API 参数数组，命名参数按 SQL 中 {@code :name} 出现顺序绑定。
     *
     * @param sql SQL 语句（用于解析命名参数顺序）
     * @return 参数数组
     */
    public Object toJson(String sql) {
        if (named != null && !named.isEmpty()) {
            return resolveNamedOrder(sql);
        }
        return getPositional();
    }

    /**
     * 按 SQL 中 {@code :name} 出现顺序解析命名参数值。
     *
     * @param sql SQL 语句
     * @return 按顺序排列的参数值数组
     */
    private Object[] resolveNamedOrder(String sql) {
        List<Object> ordered = new ArrayList<>();
        Matcher matcher = NAMED_PARAM.matcher(sql);
        while (matcher.find()) {
            var name = matcher.group(1);
            if (named.containsKey(name)) {
                ordered.add(named.get(name));
            }
        }
        return ordered.toArray();
    }

    /**
     * 是否存在参数。
     *
     * @return true 表示有位置或命名参数
     */
    public boolean hasParams() {
        return (positional != null && positional.length > 0)
                || (named != null && !named.isEmpty());
    }
}
