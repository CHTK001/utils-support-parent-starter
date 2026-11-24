package com.chua.common.support.ai.agent;

/**
 * Agent 挂起状态存储契约。
 *
 * <p><b>为什么需要它</b></p>
 * <p>Agent 的「人工确认 / 结构化提问 / 风险确认」暂停-恢复链路，需要把待恢复上下文
 * 跨请求保存。此前的实现各自持有静态缓存（agentscope 的 PENDING_BLOCKS、
 * langchain4j 的 PENDING_PAUSES）：进程内单例、无 TTL、逐出策略各自为政，
 * 长驻服务下既无法跨实例共享，也无法配置过期时间。</p>
 *
 * <p>本契约把「存什么」收敛为 {@link AgentSuspendState}（载荷不透明），
 * 把「存多久 / 存在哪」交给实现：默认提供带 TTL 与逐出的
 * {@link InMemoryAgentSuspendStore}；上层可替换为 Redis / 数据库实现以支持多实例。
 * 同一能力只此一份契约，三个 Agent 实现共同消费。</p>
 *
 * <p><b>线程安全</b>：实现必须支持并发读写。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface AgentSuspendStore extends AutoCloseable {

    /**
     * 写入或覆盖一条挂起状态。
     *
     * <p>key 已存在时覆盖旧条目（并按新条目的 TTL 重新计时）。</p>
     *
     * @param state 挂起状态条目，不能为 null
     */
    void put(AgentSuspendState state);

    /**
     * 读取挂起状态。
     *
     * <p>不存在或已过期的条目返回 null；过期条目应被顺手清除。</p>
     *
     * @param key 恢复键
     * @return 挂起状态条目；不存在或已过期返回 null
     */
    AgentSuspendState get(String key);

    /**
     * 取出并移除挂起状态（一次性消费语义）。
     *
     * <p>恢复完成后必须移除，防止同一恢复键被重复消费。
     * 不存在或已过期返回 null。</p>
     *
     * @param key 恢复键
     * @return 挂起状态条目；不存在或已过期返回 null
     */
    AgentSuspendState remove(String key);

    /**
     * 当前有效条目数（不含已过期未清扫的条目）。
     *
     * @return 条目数
     */
    int size();

    /**
     * 释放底层资源（如清扫线程、外部连接）。
     *
     * <p>默认无资源；带后台线程的实现应覆写。</p>
     */
    @Override
    default void close() {
    }

    /**
     * 进程级共享默认实例。
     *
     * <p>供未显式注入 store 的实现兜底使用：带 TTL 与条目上限的内存实现，
     * 避免各实现再各自维护静态缓存。需要跨实例共享或多实例部署时，
     * 上层应通过 {@code Agent#suspendStore(AgentSuspendStore)} 注入替代实现。</p>
     *
     * @return 共享的内存挂起状态存储
     */
    static AgentSuspendStore globalDefault() {
        return InMemoryAgentSuspendStore.GlobalHolder.INSTANCE;
    }
}
