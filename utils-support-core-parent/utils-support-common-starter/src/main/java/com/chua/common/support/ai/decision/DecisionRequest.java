package com.chua.common.support.ai.decision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次概率决策求值请求：决策上下文 + 一批问题。
 *
 * <p><b>为什么要把 state 显式放进请求</b>：概率决策要求问题
 * 挂在同一份上下文上求值。若 SPI 只暴露 {@code decide(List<DecisionQuery>)}，
 * 实现方就没有承载上下文的位置，只能退化成「每个问题自己造一份 state」——
 * 那既浪费，也破坏了「同一上下文下联合求值」这个概率决策的核心价值。
 * 因此上下文与问题列表在此绑定成一个请求对象，由实现方整体消费。</p>
 *
 * <p>{@code state} 声明为 {@link Object} 是刻意的：不同实现对上下文的
 * 承载方式不同（字符串 / 对象 / 数组各有服务接受不同形态），
 * 由实现方自行序列化，SPI 不做形状约束。</p>
 *
 * <p><b>谁该读 state——这是本包最容易被误用的一点</b>。
 * {@code state} 的定位是<b>跨进程传输的载荷</b>：实现方需要把上下文
 * 序列化后发给远端概率决策服务时，才消费它。SPI <b>不要求</b>每个实现
 * 都读它，{@link DefaultDecisionProvider} 就完全不读。因此：
 * <ul>
 *   <li><b>接远端服务</b>：把上下文放进 {@code state}，
 *       由实现方负责序列化。这是 {@code state} 唯一真正被消费的场合。</li>
 *   <li><b>本地求值（{@link DefaultDecisionProvider}）</b>：
 *       决策函数靠<b>闭包捕获</b>业务对象读数据，{@code state} 传什么都不影响结果。
 *       闭包是正规用法而非绕路——本地数据本来就在进程内、就是活对象，
 *       塞进 {@code state} 再拆出来只会多一次无意义的拆装箱。</li>
 * </ul>
 * <p>换句话说：{@code state} 为远端而存在。本地实现读不到它不是缺陷，
 * 把它当成本地求值输入才会踩空。</p>
 *
 * <p><b>批量是硬性约束</b>：一次规则集执行只构造一个请求、只调用
 * 一次 {@link DecisionProvider#decide(DecisionRequest)}。逐问题调用会让
 * 概率决策失去意义。</p>
 *
 * <p><b>关于 value class 目标</b>：本类<b>不参与</b> value class 深不可变目标。
 * 原因是 {@code state} 声明为 {@link Object}（协议允许字符串 / 对象 / 数组），
 * 而 {@code Object} 无法证明深不可变——按 {@code AGENTS.md} §3.1.2 已确认例外表，
 * 这类组件原理性不合格，不应用 {@code requireNonNull} 或拷贝去「消除」该标记。
 * {@code queries} 仍做了防御性拷贝并不可变。</p>
 *
 * @param state   决策上下文，可为 null（表示无需上下文的纯知识型问题）。
 *                定位是跨进程传输载荷，<b>仅在需要把上下文发给远端决策服务时
 *                才有意义</b>；本地实现不消费它，详见类注释
 * @param queries 问题列表，不可为空且标识互不重复
 * @author CH
 * @since 4.0.0.42
 */
public record DecisionRequest(Object state, List<DecisionQuery> queries) {

    /**
     * 构造一次决策求值请求。
     *
     * @param state   决策上下文，可为 null
     * @param queries 问题列表
     * @throws NullPointerException     {@code queries} 为 null 时
     * @throws IllegalArgumentException 问题列表为空或标识重复时
     */
    public DecisionRequest {
        Objects.requireNonNull(queries, "queries 不能为 null");
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("queries 不能为空");
        }
        Map<String, DecisionQuery> indexed = new LinkedHashMap<>();
        for (DecisionQuery query : queries) {
            if (indexed.put(query.id(), query) != null) {
                throw new IllegalArgumentException("queries 中存在重复的问题标识: " + query.id());
            }
        }
        queries = List.copyOf(queries);
    }

    /**
     * 构造一次决策求值请求。
     *
     * @param state   决策上下文，可为 null
     * @param queries 问题列表
     * @return 决策求值请求
     */
    public static DecisionRequest of(Object state, List<DecisionQuery> queries) {
        return new DecisionRequest(state, queries);
    }

    /**
     * 构造一次决策求值请求。
     *
     * @param state   决策上下文，可为 null
     * @param queries 问题列表
     * @return 决策求值请求
     */
    public static DecisionRequest of(Object state, DecisionQuery... queries) {
        return new DecisionRequest(state, List.of(queries));
    }

    /**
     * 问题数量。
     *
     * @return 问题数量
     */
    public int size() {
        return queries.size();
    }

    /**
     * 按标识查找问题。
     *
     * @param queryId 问题标识
     * @return 对应问题；不存在时返回 null
     */
    public DecisionQuery query(String queryId) {
        for (DecisionQuery query : queries) {
            if (query.id().equals(queryId)) {
                return query;
            }
        }
        return null;
    }

    /**
     * 按声明顺序返回全部问题标识。
     *
     * <p>用于诊断信息：把「这次到底问了哪些问题」一次性打出来，
     * 比在异常里逐个反查方便。</p>
     *
     * @return 不可变标识列表
     */
    public List<String> ids() {
        return Collections.unmodifiableList(queries.stream().map(DecisionQuery::id).toList());
    }
}
