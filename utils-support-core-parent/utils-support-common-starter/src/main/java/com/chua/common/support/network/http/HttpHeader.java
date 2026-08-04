package com.chua.common.support.network.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 请求/响应头封装，提供键值对存储和链式操作。
 *
 * <p>内部使用 {@link LinkedHashMap} 保持插入顺序，确保请求头按添加顺序排列。
 * 本类同时用于请求头（{@code ClientRequest}）和响应头（{@code ClientResponse}）的封装。
 * 所有请求头名称均为大小写敏感（遵循 HTTP 规范中头名称不区分大小写的约定，
 * 但底层存储保持原始大小写）。
 *
 * <p><b>使用示例：</b>
 * <pre>{@code
 * // 创建并链式添加请求头
 * HttpHeader headers = HttpHeader.create()
 *     .add("Content-Type", "application/json")
 *     .add("Authorization", "Bearer token")
 *     .add("Accept", "application/json");
 *
 * // 获取值
 * String contentType = headers.get("Content-Type");
 *
 * // 深拷贝（避免修改原始对象）
 * HttpHeader copy = headers.copy();
 * }</pre>
 *
 * <p><b>线程安全：</b>本类非线程安全，每个线程应使用自己的实例，
 * 或通过 {@link #copy()} 创建副本后使用。
 *
 * @author CH
 */
@SuppressWarnings("NullAway")
@NullUnmarked
public class HttpHeader {

    /** Content-Type */
    public static final String CONTENT_TYPE = "Content-Type";
    /** Cache-Control */
    public static final String CACHE_CONTROL = "Cache-Control";
    /** Connection */
    public static final String CONNECTION = "Connection";
    /** application/json */
    public static final String APPLICATION_JSON = "application/json";

    /**
     * 内部存储结构，使用 {@link LinkedHashMap} 保持插入顺序。
     *
     * <p>键为请求头名称（String），值为请求头值（String）。
     * 如果通过 {@link #add(String, String)} 添加同名请求头，新值会覆盖旧值。
     */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /**
     * 创建空的请求头容器。
     *
     * <p>等效于 {@code new HttpHeader()}，提供更语义化的工厂方法命名。
     *
     * @return 新的空 HttpHeader 实例
     */
    public static HttpHeader create() {
        return new HttpHeader();
    }

    /**
     * 创建包含单个键值对的请求头容器。
     *
     * <p>适用于快速创建只有一个请求头的场景。
     *
     * <p><b>使用示例：</b>
     * <pre>{@code
     * HttpHeader header = HttpHeader.of("Content-Type", "application/json");
     * }</pre>
     *
     * @param key   请求头名称，如 {@code "Content-Type"}、{@code "Authorization"}
     * @param value 请求头值，如 {@code "application/json"}、{@code "Bearer token"}
     * @return 包含指定键值对的 HttpHeader 实例
     */
    public static HttpHeader of(String key, String value) {
        return create().add(key, value);
    }

    /**
     * 添加请求头键值对。
     *
     * <p>如果已经存在同名请求头，新值会覆盖旧值（基于 {@link LinkedHashMap#put} 的语义）。
     * 此方法支持链式调用，可连续添加多个请求头。
     *
     * <p><b>null 安全：</b>如果 name 或 value 为 null，该条目会被静默忽略，不会抛出异常。
     *
     * @param name  请求头名称，如 {@code "Content-Type"}；为 null 时忽略此次操作
     * @param value 请求头值，如 {@code "application/json"}；为 null 时忽略此次操作
     * @return 当前实例（链式调用）
     */
    public HttpHeader add(String name, String value) {
        if (name != null && value != null) {
            headers.put(name, value);
        }
        return this;
    }

    /**
     * 获取指定请求头的值。
     *
     * <p>请求头名称是大小写敏感的，必须与添加时的名称完全一致才能获取到值。
     *
     * @param name 请求头名称
     * @return 请求头值，不存在返回 null
     */
    public String get(String name) {
        return headers.get(name);
    }

    /**
     * 将当前请求头转换为不可变的普通 Map。
     *
     * <p>返回的 Map 是当前请求头的一个副本（深拷贝），
     * 修改返回的 Map 不会影响原始的 HttpHeader 实例。
     *
     * @return 包含所有请求头键值对的 Map（保持插入顺序）
     */
    public Map<String, String> toMap() {
        return new LinkedHashMap<>(headers);
    }

    /**
     * 判断是否包含指定请求头。
     *
     * <p>请求头名称是大小写敏感的，必须与添加时的名称完全一致。
     *
     * @param name 请求头名称
     * @return 包含返回 true，否则返回 false
     */
    public boolean contains(String name) {
        return headers.containsKey(name);
    }

    /**
     * 获取当前请求头的数量。
     *
     * @return 请求头数量
     */
    public int size() {
        return headers.size();
    }

    /**
     * 创建当前请求头的深拷贝。
     *
     * <p>返回一个全新的 {@link HttpHeader} 实例，包含与原始实例完全相同的键值对。
     * 修改副本不会影响原始实例，适用于需要传递请求头但又不希望被外部修改的场景。
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>在多线程环境中传递请求头快照</li>
     *   <li>在请求重试时保存原始请求头</li>
     *   <li>在拦截器中复制请求头进行修改</li>
     * </ul>
     *
     * @return 新的 HttpHeader 实例，包含当前所有请求头的副本
     */
    public HttpHeader copy() {
        HttpHeader copy = new HttpHeader();
        copy.headers.putAll(this.headers);
        return copy;
    }
}