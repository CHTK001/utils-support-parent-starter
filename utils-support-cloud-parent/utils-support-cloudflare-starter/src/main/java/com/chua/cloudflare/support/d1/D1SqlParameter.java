package com.chua.cloudflare.support.d1;

import java.util.HashMap;
import java.util.Map;

/**
 * D1 SQL 参数绑定（同时支持 ? 占位符与 :name 命名参数）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class D1SqlParameter {

    /**
     * 位置参数数组（按出现顺序绑定 ?）
     */
    private final Object[] positional;

    /**
     * 命名参数 Map
     */
    private final Map<String, Object> named;

    private D1SqlParameter(Object[] positional, Map<String, Object> named) {
        this.positional = positional;
        this.named = named;
    }

    /**
     * 构造位置参数。
     *
     * @param params 参数列表（可空/可 null）
     * @return D1SqlParameter
     */
    public static D1SqlParameter ofPositional(Object[] params) {
        return new D1SqlParameter(params == null ? new Object[0] : params, null);
    }

    /**
     * 构造命名参数。
     *
     * @param named 参数映射
     * @return D1SqlParameter
     */
    public static D1SqlParameter ofNamed(Map<String, Object> named) {
        return new D1SqlParameter(new Object[0], named == null ? new HashMap<>() : new HashMap<>(named));
    }

    /**
     * 获取位置参数（{@code ?} 占位符）。
     *
     * @return 位置参数数组（不为 null）
     */
    public Object[] getPositional() {
        return positional == null ? new Object[0] : positional;
    }

    /**
     * 获取命名参数映射。
     *
     * @return 命名参数 Map
     */
    public Map<String, Object> getNamed() {
        return named == null ? new HashMap<>() : named;
    }

    /**
     * 序列化为 Cloudflare D1 API 接受的 JSON 结构。
     *
     * <p>若有名名参数，返回 {@code {"name": value, ...}}；否则返回数组。</p>
     *
     * @return JSON 节点
     */
    public Object toJson() {
        if (named != null && !named.isEmpty()) {
            return named;
        }
        return getPositional();
    }
}