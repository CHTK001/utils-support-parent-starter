package com.chua.common.support.rule;

/**
 * 事实标记接口。
 *
 * <p>「事实（Fact）」是规则引擎的输入单位，代表被规则推理的数据片段。
 * 只有实现本接口的对象才能进入工作内存（Working Memory）参与模式匹配。</p>
 *
 * <h3>与普通 POJO 的区别</h3>
 * <ul>
 *   <li>普通 POJO 只能作为条件计算的输入（属性读取）</li>
 *   <li>实现本接口后额外获得「可被规则新增、修改、撤销」的能力</li>
 *   <li>规则 RHS 中通过 {@link RuleContext#insert(Fact)} 断言新事实，
 *       引擎会在下一轮议程评估中重新匹配，支撑多级推理</li>
 * </ul>
 *
 * <h3>约束</h3>
 * <ul>
 *   <li>实现类建议同时正确实现 {@code equals}/{@code hashCode}，
 *       否则事实去重（{@link RuleSession#touch(Object)}）行为不确定</li>
 *   <li>实现类不得持有会话引用，避免与工作内存形成循环依赖</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 声明一个订单事实
 * public class OrderFact implements Fact {
 *     private final String orderId;
 *     private final long amount;
 *
 *     public OrderFact(String orderId, long amount) {
 *         this.orderId = orderId;
 *         this.amount = amount;
 *     }
 *
 *     public String getOrderId() {
 *         return orderId;
 *     }
 *
 *     public long getAmount() {
 *         return amount;
 *     }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Fact {
}
