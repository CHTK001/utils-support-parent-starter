package com.chua.common.support.ai.decision;

/**
 * 概率决策提供者扩展接口。
 *
 * <p>本包<b>自带</b>一个不依赖任何远程服务的实现：
 * {@link DefaultDecisionProvider}——由调用方按问题标识注册纯函数即可。
 * 需要接外部概率决策服务时，另建模块实现本接口即可，
 * 本包不感知具体服务，也因此不产生对任何服务 SDK 的依赖。</p>
 *
 * <p><b>只有批量方法，没有单问题方法——这是刻意的接口设计</b>。
 * 概率决策的价值来自「同一上下文下联合求值多个问题」：
 * 一次调用共享推理开销，一次返回全部问题的分布；逐问题调用不仅慢，
 * 而且各问题的概率是在不同上下文快照下独立算出的，联合阈值判断失去意义。
 * 因此本接口<b>不提供</b> {@code decide(DecisionQuery)} 单问题重载，
 * 从接口层面杜绝调用方写出 N 次往返。</p>
 *
 * <p>实现约定：</p>
 * <ol>
 *   <li>必须对 {@link DecisionRequest#queries()} 内全部问题作答，
 *       <b>不允许</b>静默跳过无法求值的问题</li>
 *   <li>无法求值时应抛 {@link DecisionException}，而不是返回缺项的批次</li>
 *   <li>返回值只需满足 {@link DecisionBatch} 的构造约束；
 *       与请求的交叉一致性由调用方调 {@link DecisionBatch#answers(DecisionRequest)} 校验</li>
 *   <li>实现必须线程安全</li>
 *   <li><b>是否消费 {@link DecisionRequest#state()} 由实现自选</b>：
 *       SPI 既不要求读、也不保证读得到。需要把上下文发给远端服务的实现
 *       必须读它并自行序列化；本地实现（{@link DefaultDecisionProvider}）
 *       完全不读，决策函数靠闭包捕获业务对象。
 *       两者都算合规实现，调用方不要假设 state 一定被用上</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DecisionProvider {

    /**
     * 对一批问题求值。
     *
     * @param request 决策求值请求，含上下文与全部问题
     * @return 决策结果批次
     * @throws DecisionException 请求非法、无法为全部问题作答或结果不自洽时
     */
    DecisionBatch decide(DecisionRequest request);
}
