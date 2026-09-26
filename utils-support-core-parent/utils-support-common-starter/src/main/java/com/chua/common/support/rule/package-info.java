/**
 * 规则引擎。
 *
 * <p>一个零三方依赖、纯 JDK 实现的<b>前向推理生产规则系统</b>，
 * 概念与执行模型对齐 Drools（Production Memory / Working Memory / Agenda，
 * LHS / RHS，salience、agenda-group、activation-group、no-loop、lock-on-active），
 * 但不依赖 Drools 运行时，可在 common 层直接使用。</p>
 *
 * <h2>与「断路器」的本质区别</h2>
 * <p>模块内已有 {@code com.chua.common.support.concurrent.circuit.CircuitBreaker}，
 * 它名为 CircuitBreaker，实际是一个<b>通用条件断路器</b>：表达式经 SPI 解析为
 * B-Tree，叶子由判断器求值，短路得到一个 boolean（true 放行 / false 断路），
 * 并支持 expr / sql / lucene / cypher 等表达式类型。</p>
 *
 * <p>本包不是它的替代品，而是它的<b>上位形态</b>——
 * 断路器恰好是规则引擎的一组退化特例：</p>
 * <table border="1">
 *   <caption>表达式断路器 与 规则引擎</caption>
 *   <tr><th>维度</th><th>表达式断路器</th><th>规则引擎</th></tr>
 *   <tr><td>规则条数</td><td>1 条表达式</td><td>N 条规则竞争</td></tr>
 *   <tr><td>记忆</td>
 *       <td>无状态，计数等由调用方塞进 {@code context}</td>
 *       <td>工作内存中的 {@link com.chua.common.support.rule.Fact} 跨请求存活</td></tr>
 *   <tr><td>求值时机</td><td>一次性立即求值</td><td>多轮 fire 直到不动点</td></tr>
 *   <tr><td>输出</td><td>仅 boolean</td>
 *       <td>结论 + 结果条目 + 列表 + 统计</td></tr>
 *   <tr><td>冲突</td><td>不存在（单条无竞争）</td>
 *       <td>salience / agenda-group / activation-group 冲突消解</td></tr>
 *   <tr><td>竞争结果</td><td>无此概念</td>
 *       <td>「哪条规则说了算」本身是一个决策</td></tr>
 *   <tr><td>组合维度</td><td>仅 AND / OR / NOT</td>
 *       <td>跨轮、跨绑定元组、焦点栈、版本折射</td></tr>
 *   <tr><td>失败语义</td><td>异常直接 return false（整体切断）</td>
 *       <td>单规则异常被隔离计数，其他规则照常执行</td></tr>
 *   <tr><td>可观测</td><td>无</td>
 *       <td>8 类 {@link com.chua.common.support.rule.RuleEvent}</td></tr>
 * </table>
 *
 * <p>一句话：<b>断路器回答「这一个条件成不成立」；
 * 规则引擎回答「在这一堆事实上，多条规则的条件都成立后，谁该说话、
 * 说完世界变成什么样、然后又有哪些规则因此被激活」。</b></p>
 *
 * <p>需要与表达式断路器<b>同形替换</b>时，用
 * {@link com.chua.common.support.rule.RuleBreaker}；
 * 它保留了表达式断路器的 fail-closed 保守语义。</p>
 *
 * <h2>与其他「规则形态」能力的边界</h2>
 * <ul>
 *   <li>{@code concurrent.circuit.CircuitBreaker} — 无状态表达式断路器，本包的上位形态</li>
 *   <li>{@code concurrent.circuitbreaker.CircuitBreakerFlow} —
 *       <b>熔断器</b>（韧性模式：失败阈值 / 冷却 / 半开探测），
 *       属于另一诉求，热路径场景应优先用它，勿用本包替代</li>
 *   <li>{@code network.server.filter.ResponseRewriteFilter.RewriteRule}
 *       — 一组响应改写回调，按注册顺序线性执行</li>
 *   <li>{@code serialize.DenyListDeserializationPolicy} — 固定黑名单</li>
 *   <li>{@code spi.ConditionEvaluator} — 仅服务于 SPI 加载条件</li>
 * </ul>
 *
 * <h2>核心类型</h2>
 * <ul>
 *   <li>{@link com.chua.common.support.rule.RuleBase} — 规则库（Production Memory），不可变可共享</li>
 *   <li>{@link com.chua.common.support.rule.RuleSession} — 会话（Working Memory + Agenda）</li>
 *   <li>{@link com.chua.common.support.rule.Rule} — 规则定义（LHS + RHS + 控制属性）</li>
 *   <li>{@link com.chua.common.support.rule.Condition} /
 *       {@link com.chua.common.support.rule.Pattern} — 条件与事实模式</li>
 *   <li>{@link com.chua.common.support.rule.Action} /
 *       {@link com.chua.common.support.rule.Actions} — 动作</li>
 *   <li>{@link com.chua.common.support.rule.RuleContext} — 推理上下文</li>
 *   <li>{@link com.chua.common.support.rule.RuleListener} /
 *       {@link com.chua.common.support.rule.RuleEvent} — 事件观测</li>
 *   <li>{@link com.chua.common.support.rule.RuleBreaker} — 规则断路器门面</li>
 * </ul>
 *
 * <h2>快速开始</h2>
 * <pre>{@code
 * Rule rule = Rule.builder("大额订单转人工")
 *         .salience(100)
 *         .when(Pattern.of("order", OrderFact.class, o -> o.getAmount() > 100_000))
 *         .then(Actions.setResult("NEED_MANUAL_REVIEW"))
 *         .build();
 *
 * try (RuleSession session = RuleBase.of(rule).newSession()) {
 *     session.insert(new OrderFact("A100", 200_000));
 *     session.fire();
 *     System.out.println(session.result()); // NEED_MANUAL_REVIEW
 * }
 * }</pre>
 *
 * <h2>作为断路器使用</h2>
 * <pre>{@code
 * Rule denyBlocked = Rule.builder("黑名单断路")
 *         .when(Pattern.of("user", UserFact.class, UserFact::isBlocked))
 *         .then(Actions.deny())
 *         .build();
 *
 * boolean pass = RuleBreaker.of(denyBlocked).evaluate(new UserFact("u1"));
 * }</pre>
 */
package com.chua.common.support.rule;
