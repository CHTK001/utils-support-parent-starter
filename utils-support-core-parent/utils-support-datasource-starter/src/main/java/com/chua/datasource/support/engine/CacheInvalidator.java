package com.chua.datasource.support.engine;

/**
 * 缓存失效通知钩子（可选扩展点）。
 *
 * <p>{@link CachingEngine} 的副本只服务于本进程：本实例写入后由它自己整表失效，
 * 因此单实例场景不需要任何额外配合。但多个应用实例同时挂在同一个数据库上时，
 * A 实例写入后 B 实例的内存副本仍然是旧快照，直到 B 自己的 TTL 到期或被
 * {@link CachingEngine#reload(String)} 显式刷新。</p>
 *
 * <p>需要跨实例强一致时，由宿主实现本接口（例如向 Redis/消息总线广播表名），
 * 再通过 {@link CachingEngine#addInvalidator(CacheInvalidator)} 注册；
 * 收到远端通知的实例调用 {@link CachingEngine#reload(String)} 即可翻回最新快照。
 * 默认实现 {@link #NONE} 什么都不做，因此不注册时行为与单实例完全一致。</p>
 *
 * <p>本接口只做「通知」，不承载失效动作本身：失效动作由
 * {@link CachingEngine#reload(String)} 定义，避免同一能力出现两份实现。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@FunctionalInterface
public interface CacheInvalidator {

    /**
     * 空实现：不做任何跨实例通知，单实例语义。
     */
    CacheInvalidator NONE = table -> {
    };

    /**
     * 本实例刚刚失效了一张表的缓存副本。
     *
     * <p>实现方通常在此把表名广播给其它实例。本方法在写入成功后同步调用，
     * 抛出的 {@link RuntimeException} 会被 {@link CachingEngine} 捕获并只记录日志——
     * 通知失败不能让已经提交成功的写入变成调用方眼中的失败。</p>
     *
     * @param table 被失效的表名；{@code null} 表示「无法定位到具体表，全部失效」
     *              （例如原生 {@code executeSql} 无法从语句中可靠解析目标表）
     */
    void onInvalidate(String table);
}
