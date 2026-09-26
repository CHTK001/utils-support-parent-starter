package com.chua.common.support.rule;

/**
 * 规则引擎门面。
 *
 * <p>一个<b>类型</b>，不是继承来的基类：{@link RuleSession} 是具体实现，
 * 本接口只暴露「装载规则 → 执行判定」这条主干，让调用方依赖抽象而非具体类。
 * 这样将来换推理内核（Drools / DMN / 自研）时，调用方代码不必改。</p>
 *
 * <h3>与 RuleSession / RuleBreaker 的分工</h3>
 * <ul>
 *   <li>{@link RuleSession}：一次有状态的推理过程，可反复 {@code fire}，
 *       适合多轮推导、需要掌控工作内存的场景；</li>
 *   <li>{@link RuleBreaker}：把一次推理压成「放行 / 断路」的布尔门禁，
 *       适合风控、限流这类只要一个结论的场合；</li>
 *   <li>{@code RuleEngine}：面向「规则集本身」的门面，
 *       管的是规则从哪来、怎么装、怎么重载。</li>
 * </ul>
 *
 * <h3>实现必须线程安全</h3>
 * <p>规则集不可变、每次调用创建独立会话，因此实现应当是无状态的，
 * 可被多个线程并发调用。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface RuleEngine {

    /**
     * 规则版本号，每次成功装载递增。
     *
     * @return 版本号，尚未装载时为 0
     */
    long version();

    /**
     * 当前生效的规则库。
     *
     * @return 规则库，尚未装载返回 null
     */
    RuleBase ruleBase();

    /**
     * 创建一个新的推理会话。
     *
     * <p>会话持有独立的工作内存，调用方负责 {@link RuleSession#close()}。
     * 本方法不负责热更新：会话在创建时固定当时的规则集，
     * 单次推理不会混用两个版本。</p>
     *
     * @return 推理会话
     * @throws RuleException 尚无可用规则集时抛出
     */
    RuleSession newSession();

    /**
     * 一次性判定，返回放行与否。
     *
     * @param facts 事实数组
     * @return true 表示放行
     */
    boolean evaluate(Fact... facts);

    /**
     * 一次性判定，返回放行与否。
     *
     * @param facts 事实集合
     * @return true 表示放行
     */
    boolean evaluate(java.util.Collection<? extends Fact> facts);

    /**
     * 一次性判定并返回完整结论。
     *
     * @param facts 事实数组
     * @return 判定结果
     */
    RuleBreaker.Verdict verdict(Fact... facts);

    /**
     * 重新装载规则来源。
     *
     * <p>装载失败时<b>不</b>替换规则集，引擎继续使用上一份可用规则，
     * 并可通过 {@link #lastError()} 查到原因——这保证一份写错的规则文件
     * 不会让线上门禁整体失效。</p>
     *
     * @return 变更后的版本号
     * @throws RuleException 没有已装载来源时抛出
     */
    long reload();

    /**
     * 最近一次装载错误。
     *
     * @return 错误，无错误返回 null
     */
    Throwable lastError();
}
