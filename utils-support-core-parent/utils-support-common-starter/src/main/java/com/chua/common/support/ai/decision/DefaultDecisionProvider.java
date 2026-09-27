package com.chua.common.support.ai.decision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 由调用方提供函数实现的决策提供者。
 *
 * <p>这是 {@link DecisionProvider} 的默认实现，也是<b>规则条件层在无外部
 * 概率决策服务时的落地方式</b>：调用方按问题标识注册一个纯函数，
 * 规则层照常走「汇总全部问题 → 一次调用 → 展平为上下文变量」的流程，
 * 不需要为「本地决策」和「远程决策」写两套规则代码。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * Set<String> blacklist = Set.of("u-9527", "u-1024");
 * String userId = "u-9527";
 *
 * Map<String, Function<DecisionQuery, Decision>> fns = new LinkedHashMap<>();
 * fns.put("fraud", q -> Decision.noul(q.id(), blacklist.contains(userId) ? 0.95D : 0.05D));
 * fns.put("channel", q -> Decision.of(q.id(), DecisionType.CHOICE, "alipay",
 *                                 new LinkedHashMap<>(Map.of("alipay", 0.7D, "wechat", 0.3D))));
 *
 * DecisionProvider provider = DefaultDecisionProvider.of(fns);
 * }</pre>
 *
 * <p><b>本实现完全忽略 {@link DecisionRequest#state()}</b>：
 * 决策函数签名是 {@code Function<DecisionQuery, Decision>}，
 * {@link #decide(DecisionRequest)} 只把 {@code query} 传进去，
 * 上下文不参与求值。这是<b>有意为之</b>，不是遗漏。
 * 决策函数通过<b>闭包捕获</b>直接引用业务对象（上面的 {@code userId}、
 * {@code blacklist} 就是被捕获的变量），
 * 本地数据在进程内、就是活对象，经 {@code state} 拆箱再装箱纯属多余。
 * {@code state} 是留给需要把它序列化后发给远端决策服务的实现用的，
 * 见 {@link DecisionProvider} 的实现约定第 5 条。
 * 需要读 state 的实现请另写 {@link DecisionProvider} 实现。</p>
 *
 * <p><b>线程安全</b>：函数表在构造期拷贝为不可变，此后只读，可安全并发调用。
 * 线程安全责任下移给调用方注册的函数——本类不包装也不加锁。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultDecisionProvider implements DecisionProvider {

    /**
     * 问题标识到决策函数的映射
     */
    private final Map<String, Function<DecisionQuery, Decision>> functions;

    /**
     * 用调用方提供的函数表构造决策提供者。
     *
     * <p>函数表在构造期拷贝为不可变，避免调用方事后改写原始 Map
     * 导致已创建的提供者行为突变。</p>
     *
     * @param functions 问题标识到决策函数的映射，不可为 null、不可包含 null 值
     * @throws NullPointerException     {@code functions} 为 null 时
     * @throws IllegalArgumentException 函数表为空或包含 null 键 / null 值时
     */
    public DefaultDecisionProvider(Map<String, Function<DecisionQuery, Decision>> functions) {
        Objects.requireNonNull(functions, "functions 不能为 null");
        if (functions.isEmpty()) {
            throw new IllegalArgumentException("functions 不能为空");
        }
        Map<String, Function<DecisionQuery, Decision>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Function<DecisionQuery, Decision>> entry : functions.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("functions 的键不能为 null 或空白");
            }
            if (entry.getValue() == null) {
                throw new NullPointerException("functions 中 " + entry.getKey() + " 的决策函数不能为 null");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        this.functions = Collections.unmodifiableMap(copy);
    }

    /**
     * 用调用方提供的函数表构造决策提供者。
     *
     * @param functions 问题标识到决策函数的映射
     * @return 决策提供者
     */
    public static DefaultDecisionProvider of(Map<String, Function<DecisionQuery, Decision>> functions) {
        return new DefaultDecisionProvider(functions);
    }

    /**
     * 对一批问题求值。
     *
     * <p>严格逐个调用注册函数，但对外仍是<b>一次</b> {@code decide} 调用——
     * 这正是本接口不暴露单问题方法的用意：批量语义由接口保证，
     * 本地实现怎么高效是实现细节。</p>
     *
     * <p><b>注意 {@code request.state()} 在本实现中不被消费</b>，
     * 详见类注释。求值所需数据由注册函数的闭包提供。</p>
     *
     * <p>逐项校验：函数缺失、返回 null、返回的答案标识与问题标识不符，
     * 都在此抛出 {@link DecisionException}。标识不符这一项尤其重要——
     * 它能当场抓住「把同一个 {@code Decision} 实例返回给多个问题」
     * 这个极易犯且后果隐蔽的错误。</p>
     *
     * @param request 决策求值请求
     * @return 决策结果批次
     * @throws DecisionException 未注册对应函数、函数返回 null 或答案标识不匹配时
     */
    @Override
    public DecisionBatch decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request 不能为 null");
        List<Decision> decisions = new ArrayList<>(request.size());
        for (DecisionQuery query : request.queries()) {
            Function<DecisionQuery, Decision> function = functions.get(query.id());
            if (function == null) {
                throw new DecisionException(query.id(), "未注册决策函数，已注册的标识: " + functions.keySet());
            }
            Decision decision;
            try {
                decision = function.apply(query);
            } catch (RuntimeException e) {
                throw new DecisionException("决策函数执行失败: " + e.getMessage(), e);
            }
            if (decision == null) {
                throw new DecisionException(query.id(), "决策函数返回了 null");
            }
            if (!query.id().equals(decision.queryId())) {
                throw new DecisionException(query.id(),
                        "决策函数返回的答案标识为 " + decision.queryId() + "，与问题标识不符");
            }
            decisions.add(decision);
        }
        return new DecisionBatch(decisions);
    }

    /**
     * 已注册的问题标识。
     *
     * @return 不可变标识列表
     */
    public List<String> registeredIds() {
        return List.copyOf(functions.keySet());
    }
}
