package com.chua.common.support.rule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * 条件工厂与组合实现。
 *
 * <p>提供纯谓词条件的构造入口，以及 AND / OR / NOT 组合条件的具体实现。
 * 组合实现全部支持短路求值。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * Condition condition = Conditions.and(
 *         Pattern.of("order", OrderFact.class, o -> o.getAmount() > 1000),
 *         Pattern.of("vip", VipFact.class, VipFact::isVip),
 *         Conditions.not(Pattern.of("blocked", BlockedFact.class))
 * );
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class Conditions {

    /**
     * 工具类禁止实例化
     */
    private Conditions() {
    }

    /**
     * 恒真条件。
     *
     * @return 恒返回 true 的条件
     */
    public static Condition alwaysTrue() {
        return context -> true;
    }

    /**
     * 恒假条件。
     *
     * @return 恒返回 false 的条件
     */
    public static Condition alwaysFalse() {
        return context -> false;
    }

    /**
     * 由布尔值构造条件。
     *
     * @param value 固定结果
     * @return 恒返回该值的条件
     */
    public static Condition of(boolean value) {
        return context -> value;
    }

    /**
     * 由断言构造条件。
     *
     * @param predicate 断言
     * @return 委托给断言的条件
     */
    public static Condition of(Predicate<RuleContext> predicate) {
        if (predicate == null) {
            throw new RuleException("断言不能为 null");
        }
        return predicate::test;
    }

    /**
     * 比较条件：属性大于指定值。
     *
     * @param <T>     值类型
     * @param selector 值提取函数
     * @param bound    边界值
     * @return 大于边界时成立的条件
     */
    public static <T extends Comparable<T>> Condition gt(java.util.function.Function<RuleContext, T> selector, T bound) {
        if (selector == null) {
            throw new RuleException("值提取函数不能为 null");
        }
        return context -> {
            T value = selector.apply(context);
            return value != null && value.compareTo(bound) > 0;
        };
    }

    /**
     * 比较条件：属性小于指定值。
     *
     * @param <T>     值类型
     * @param selector 值提取函数
     * @param bound    边界值
     * @return 小于边界时成立的条件
     */
    public static <T extends Comparable<T>> Condition lt(java.util.function.Function<RuleContext, T> selector, T bound) {
        if (selector == null) {
            throw new RuleException("值提取函数不能为 null");
        }
        return context -> {
            T value = selector.apply(context);
            return value != null && value.compareTo(bound) < 0;
        };
    }

    /**
     * 取反条件。
     *
     * @param condition 被取反的条件
     * @return NOT 条件
     */
    public static Condition not(Condition condition) {
        if (condition == null) {
            throw new RuleException("被取反的条件不能为 null");
        }
        return new NotCondition(condition);
    }

    /**
     * AND 条件。
     *
     * @param conditions 子条件
     * @return AND 条件
     */
    public static Condition and(Condition... conditions) {
        return and(Arrays.asList(conditions));
    }

    /**
     * AND 条件。
     *
     * @param conditions 子条件
     * @return AND 条件
     */
    public static Condition and(List<? extends Condition> conditions) {
        if (conditions == null) {
            throw new RuleException("子条件集合不能为 null");
        }
        List<Condition> copy = new ArrayList<>(conditions.size());
        for (Condition condition : conditions) {
            if (condition == null) {
                throw new RuleException("子条件不能为 null");
            }
            copy.add(condition);
        }
        if (copy.isEmpty()) {
            return alwaysTrue();
        }
        if (copy.size() == 1) {
            return copy.get(0);
        }
        return new AndCondition(List.copyOf(copy));
    }

    /**
     * OR 条件。
     *
     * @param conditions 子条件
     * @return OR 条件
     */
    public static Condition or(Condition... conditions) {
        return or(Arrays.asList(conditions));
    }

    /**
     * OR 条件。
     *
     * @param conditions 子条件
     * @return OR 条件
     */
    public static Condition or(List<? extends Condition> conditions) {
        if (conditions == null) {
            throw new RuleException("子条件集合不能为 null");
        }
        List<Condition> copy = new ArrayList<>(conditions.size());
        for (Condition condition : conditions) {
            if (condition == null) {
                throw new RuleException("子条件不能为 null");
            }
            copy.add(condition);
        }
        if (copy.isEmpty()) {
            return alwaysFalse();
        }
        if (copy.size() == 1) {
            return copy.get(0);
        }
        return new OrCondition(List.copyOf(copy));
    }

    /**
     * 展开顶层 AND 条件，供引擎做元组（笛卡尔积）匹配。
     *
     * <p>若传入条件是 AND 组合，则返回其子条件列表；
     * 否则返回仅含该条件的单元素列表。这样引擎可以统一按
     * 「顶层子条件」处理模式匹配与谓词求值。</p>
     *
     * @param condition 待展开条件
     * @return 顶层子条件列表，永不为 null
     */
    public static List<Condition> flatten(Condition condition) {
        if (condition == null) {
            throw new RuleException("待展开的条件不能为 null");
        }
        if (condition instanceof AndCondition andCondition) {
            return andCondition.conditions();
        }
        return List.of(condition);
    }

    /**
     * AND 条件实现。
     */
    static final class AndCondition implements Condition {

        /**
         * 子条件列表
         */
        private final List<Condition> conditions;

        /**
         * 创建 AND 条件。
         *
         * @param conditions 子条件列表
         */
        private AndCondition(List<Condition> conditions) {
            this.conditions = conditions;
        }

        /**
         * 获取子条件列表，供引擎做元组展开。
         *
         * @return 子条件列表
         */
        List<Condition> conditions() {
            return conditions;
        }

        @Override
        public boolean test(RuleContext context) {
            for (Condition condition : conditions) {
                if (!condition.test(context)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * OR 条件实现。
     */
    private static final class OrCondition implements Condition {

        /**
         * 子条件列表
         */
        private final List<Condition> conditions;

        /**
         * 创建 OR 条件。
         *
         * @param conditions 子条件列表
         */
        private OrCondition(List<Condition> conditions) {
            this.conditions = conditions;
        }

        @Override
        public boolean test(RuleContext context) {
            for (Condition condition : conditions) {
                if (condition.test(context)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * NOT 条件实现。
     */
    private static final class NotCondition implements Condition {

        /**
         * 被取反的条件
         */
        private final Condition condition;

        /**
         * 创建 NOT 条件。
         *
         * @param condition 被取反的条件
         */
        private NotCondition(Condition condition) {
            this.condition = condition;
        }

        @Override
        public boolean test(RuleContext context) {
            return !condition.test(context);
        }
    }
}
