package com.chua.common.support.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 动作工厂。
 *
 * <p>提供断言事实、修改事实、撤销事实、记录结果等常用动作的构造入口，
 * 以及会话级动作（清空结果、重置计数器等）。</p>
 *
 * <h3>作为断路器使用</h3>
 * <p>{@link #allow()} / {@link #deny()} 产出放行与断路结论，
 * 配合 {@link RuleBreaker} 即可把规则引擎用作通用条件断路器：</p>
 * <pre>{@code
 * Rule rule = Rule.builder("黑名单直接断路")
 *         .when(Pattern.of("user", UserFact.class, u -> u.isBlacklisted()))
 *         .then(Actions.deny())
 *         .build();
 *
 * boolean pass = RuleBreaker.of(rule).evaluate(new UserFact("u1"));
 * }</pre>
 *
 * <pre>{@code
 * Rule rule = Rule.builder("高危等级断路")
 *         .when(Pattern.of("user", UserFact.class))
 *         .then(Actions.allowUnless(ctx -> ctx.get("user", UserFact.class).getLevel() >= 3))
 *         .build();
 * }</pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Rule rule = Rule.builder("大额订单人工复核")
 *         .when(Pattern.of("order", OrderFact.class, o -> o.getAmount() > 100_000))
 *         .then(Actions.all(
 *                 Actions.insert(new AuditFact("大额订单")),
 *                 Actions.setResult("NEED_MANUAL_REVIEW"),
 *                 Actions.retract("order")
 *         ))
 *         .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Actions {

    /**
     * 放行结论
     */
    public static final String ALLOW = "ALLOW";

    /**
     * 断路结论
     */
    public static final String DENY = "DENY";

    /**
     * 工具类禁止实例化
     */
    private Actions() {
    }

    /**
     * 组合多个动作，按声明顺序依次执行。
     *
     * @param actions 待组合的动作
     * @return 组合后的动作
     */
    public static Action all(Action... actions) {
        if (actions == null || actions.length == 0) {
            return context -> {
            };
        }
        return context -> {
            for (Action action : actions) {
                if (action != null) {
                    action.execute(context);
                }
            }
        };
    }

    /**
     * 组合多个动作，按声明顺序依次执行。
     *
     * @param actions 待组合的动作
     * @return 组合后的动作
     */
    public static Action all(List<? extends Action> actions) {
        if (actions == null || actions.isEmpty()) {
            return context -> {
            };
        }
        List<Action> copy = List.copyOf(actions);
        return context -> {
            for (Action action : copy) {
                if (action != null) {
                    action.execute(context);
                }
            }
        };
    }

    /**
     * 构造「插入事实」动作。
     *
     * <p>插入的事实必须实现 {@link Fact}，否则会话会拒绝并记一次失败。</p>
     *
     * @param fact 待插入的事实
     * @return 动作
     */
    public static Action insert(Fact fact) {
        if (fact == null) {
            throw new RuleException("待插入的事实不能为 null");
        }
        return context -> context.insert(fact);
    }

    /**
     * 构造「由工厂创建并插入事实」动作。
     *
     * <p>工厂在动作执行时才被调用，便于基于当前上下文动态构造事实。</p>
     *
     * @param factory 事实工厂
     * @return 动作
     */
    public static Action insert(Supplier<? extends Fact> factory) {
        if (factory == null) {
            throw new RuleException("事实工厂不能为 null");
        }
        return context -> context.insert(factory.get());
    }

    /**
     * 构造「撤销事实」动作。
     *
     * @param binding 事实绑定名
     * @return 动作
     */
    public static Action retract(String binding) {
        if (binding == null || binding.isBlank()) {
            throw new RuleException("撤销事实的绑定名不能为空");
        }
        return context -> context.retract(binding);
    }

    /**
     * 构造「修改事实属性」动作。
     *
     * @param binding    事实绑定名
     * @param property   属性名
     * @param newValue   新值
     * @return 动作
     */
    public static Action set(String binding, String property, Object newValue) {
        if (binding == null || binding.isBlank()) {
            throw new RuleException("修改事实的绑定名不能为空");
        }
        if (property == null || property.isBlank()) {
            throw new RuleException("修改事实的属性名不能为空");
        }
        return context -> context.setProperty(binding, property, newValue);
    }

    /**
     * 构造「对已绑定事实执行消费者」动作。
     *
     * @param binding  事实绑定名
     * @param consumer 消费者
     * @param <T>      事实类型
     * @return 动作
     */
    @SuppressWarnings("unchecked")
    public static <T> Action with(String binding, Consumer<T> consumer) {
        if (binding == null || binding.isBlank()) {
            throw new RuleException("绑定名不能为空");
        }
        if (consumer == null) {
            throw new RuleException("消费者不能为 null");
        }
        return context -> {
            Object bound = context.get(binding);
            if (bound != null) {
                consumer.accept((T) bound);
            }
        };
    }

    /**
     * 构造「放行」结论动作。
     *
     * <p>这是把规则引擎用作断路器时的核心动作：
     * 规则命中即产出 {@link #ALLOW} 结论，
     * {@link RuleBreaker#evaluate} 据此返回 {@code true}。</p>
     *
     * @return 动作
     */
    public static Action allow() {
        return context -> context.setResult(ALLOW);
    }

    /**
     * 构造「断路」结论动作。
     *
     * @return 动作
     */
    public static Action deny() {
        return context -> context.setResult(DENY);
    }

    /**
     * 构造「按条件放行」动作：条件成立放行，否则断路。
     *
     * <p>适合把一条规则直接写成门禁：
     * 条件命中黑名单特征时断路，其余放行。</p>
     *
     * <p><b>注意</b>：条件里要读取事实，必须配套使用<b>具名</b>模式
     * （{@code Pattern.of("user", UserFact.class)}），
     * 匿名模式（{@code Pattern.of(UserFact.class)}）只判断存在性、不写入绑定，
     * 此时 {@code context.get("user")} 会得到 null。</p>
     *
     * @param gate 断路条件，成立则断路
     * @return 动作
     */
    public static Action allowUnless(Condition gate) {
        if (gate == null) {
            throw new RuleException("断路条件不能为 null");
        }
        return context -> context.setResult(gate.test(context) ? DENY : ALLOW);
    }

    /**
     * 构造「按条件断路」动作：条件成立断路，否则放行。
     *
     * <p>关于具名模式的注意事项同 {@link #allowUnless(Condition)}。</p>
     *
     * @param gate 放行条件，不成立则断路
     * @return 动作
     */
    public static Action denyUnless(Condition gate) {
        if (gate == null) {
            throw new RuleException("放行条件不能为 null");
        }
        return context -> context.setResult(gate.test(context) ? ALLOW : DENY);
    }

    /**
     * 构造「设置本轮推理结果」动作。
     *
     * @param value 结果值
     * @return 动作
     */
    public static Action setResult(Object value) {
        return context -> context.setResult(value);
    }

    /**
     * 构造「记录日志式结果条目」动作。
     *
     * @param key   条目键
     * @param value 条目值
     * @return 动作
     */
    public static Action record(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new RuleException("结果条目键不能为空");
        }
        return context -> context.record(key, value);
    }

    /**
     * 构造「批量追加结果条目」动作。
     *
     * @param entries 条目集合
     * @return 动作
     */
    public static Action recordAll(Map<String, Object> entries) {
        if (entries == null) {
            throw new RuleException("条目集合不能为 null");
        }
        Map<String, Object> copy = new LinkedHashMap<>(entries);
        return context -> {
            for (Map.Entry<String, Object> entry : copy.entrySet()) {
                context.record(entry.getKey(), entry.getValue());
            }
        };
    }

    /**
     * 构造「停止本轮推理」动作。
     *
     * <p>调用后当前规则剩余动作与本轮后续规则均不再执行，
     * 但已执行的动作不回滚。</p>
     *
     * @return 动作
     */
    public static Action halt() {
        return RuleContext::halt;
    }

    /**
     * 条件动作：条件成立时执行。
     *
     * @param condition 执行条件
     * @param action    被执行的动作
     * @return 动作
     */
    public static Action when(Condition condition, Action action) {
        if (condition == null || action == null) {
            throw new RuleException("条件与动作均不能为 null");
        }
        return context -> {
            if (condition.test(context)) {
                action.execute(context);
            }
        };
    }

    /**
     * 构造「向列表追加元素」动作。
     *
     * @param target 目标列表键，缺省为 {@link RuleContext#result()} 对应列表
     * @return 动作
     */
    public static Action list(String target) {
        String key = (target == null || target.isBlank()) ? RuleContext.DEFAULT_LIST_KEY : target;
        return context -> context.list(key);
    }

    /**
     * 构造「向列表追加元素」动作。
     *
     * @param key     列表键
     * @param element 待追加元素
     * @return 动作
     */
    public static Action addToList(String key, Object element) {
        if (key == null || key.isBlank()) {
            throw new RuleException("列表键不能为空");
        }
        return context -> context.addToList(key, element);
    }

    /**
     * 构造「追加多个元素到列表」动作。
     *
     * @param key      列表键
     * @param elements 待追加元素
     * @return 动作
     */
    public static Action addAllToList(String key, List<?> elements) {
        if (key == null || key.isBlank()) {
            throw new RuleException("列表键不能为空");
        }
        if (elements == null) {
            throw new RuleException("元素集合不能为 null");
        }
        List<Object> copy = new ArrayList<>(elements);
        return context -> {
            for (Object element : copy) {
                context.addToList(key, element);
            }
        };
    }
}
