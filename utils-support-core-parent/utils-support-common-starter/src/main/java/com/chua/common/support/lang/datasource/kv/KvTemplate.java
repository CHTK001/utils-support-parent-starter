package com.chua.common.support.lang.datasource.kv;

import java.time.Duration;

/**
* KV 链式操作模板，包裹 {@link KvEngine} 实现，提供流畅（Fluent）API。
*
* <p><b>已废弃</b>：请使用 {@link KvEngine} 接口替代。{@code KvTemplate} 的所有功能
* 已由 {@link KvEngine} 的 {@link KvEngine#key(String)} 方法直接提供，无需额外模板层。</p>
*
* @deprecated 使用 {@link KvEngine} 接口替代。直接注入 {@code KvEngine} 并通过
*             {@code engine.key("xxx").expire(3600).set("v")} 完成链式操作。
* @author CH
* @since 4.0.0.42
 */
@Deprecated(forRemoval = true, since = "4.0.0.42")
public class KvTemplate {

    /**
    * 底层 KV 引擎实现
    */
    private final KvEngine engine;

    /**
    * 根据底层引擎构造链式模板。
    *
    * @param engine 底层 KV 引擎实现，不可为 null
    */
    @Deprecated(forRemoval = true, since = "4.0.0.42")
    public KvTemplate(KvEngine engine) {
        this.engine = engine;
    }

    /**
    * 绑定到指定键，返回键级链式操作器。
    *
    * @param key 目标键，不可为 null
    * @return 键级操作器
    */
    public KvKeyOps key(String key) {
        return new KvKeyOps(engine, key);
    }

    /**
    * 获取底层原始 KV 引擎实现。
    *
    * @return 底层 KvEngine 实例
    */
    public KvEngine raw() {
        return engine;
    }

    /**
     * 获取
     * @param key 键，不允许为 null
     * @return 结果字符串
     */
    public String get(String key) {
        return engine.get(key);
    }

    /**
     * Put
     * @param key 键，不允许为 null
     * @param value 值，不允许为 null
     */
    public void put(String key, String value) {
        engine.put(key, value);
    }

    /**
     * Put
     * @param key 键，不允许为 null
     * @param value 值，不允许为 null
     * @param ttl 方法入参 ttl
     */
    public void put(String key, String value, Duration ttl) {
        engine.put(key, value, ttl);
    }

    /**
     * ContainsKey
     * @param key 键，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    public boolean containsKey(String key) {
        return engine.containsKey(key);
    }

    /**
     * 删除
     * @param key 键，不允许为 null
     * @return 是否成功（true 表示成功）
     */
    public boolean delete(String key) {
        return engine.delete(key);
    }

    /**
     * Incr
     * @param key 键，不允许为 null
     * @return 结果数值
     */
    public long incr(String key) {
        return engine.incr(key);
    }

    /**
     * Ttl
     * @param key 键，不允许为 null
     * @return 结果数值
     */
    public long ttl(String key) {
        return engine.ttl(key);
    }

    /**
     * Expire
     * @param key 键，不允许为 null
     * @param seconds 方法入参 seconds
     */
    public void expire(String key, long seconds) {
        engine.expire(key, seconds);
    }
}
