package com.chua.common.support.rule.file;

import com.chua.common.support.rule.RuleException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则文件条件树定义。
 *
 * <p>对应规则文件 {@code when} 段。条件树是可组合的节点，
 * 由 {@link RuleAssembler} 翻译为运行时的
 * {@link com.chua.common.support.rule.Condition}。</p>
 *
 * <h3>节点形态</h3>
 * <pre>{@code
 * // 事实声明（顶层 when.facts 的元素）
 * { "type": "order", "binding": "o", "where": { ...条件树... } }
 *
 * // AND
 * { "all": [ 节点, 节点 ] }
 * // OR
 * { "any": [ 节点, 节点 ] }
 * // NOT
 * { "not": 节点 }
 * // 属性比较（走 ReflectUtils，支持 a.b 路径）
 * { "path": "amount", "op": "GT", "value": 100000 }
 * // 绑定存在性
 * { "exists": "o" }
 * // 全局变量比较
 * { "global": "maxAmount", "op": "GT", "value": 1000 }
 * // 表达式（由 ASM 编译为字节码，见 codegen 包）
 * { "expr": "o.amount > g.maxAmount" }
 * }</pre>
 *
 * <h3>与引擎元组语义的对应</h3>
 * <p>顶层 {@code when.facts} 中声明的多个事实，会被翻译为多个具名
 * {@link com.chua.common.support.rule.Pattern}，由引擎做笛卡尔积，
 * 每个绑定组合触发一次规则。顶层 {@code when.where} 则是跨绑定的谓词，
 * 在每个元组上求值。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class RuleConditionSpec {

    /**
     * 节点类型
     */
    public enum Kind {

        /**
         * 事实定义：声明一个事实类型并绑定到名字（仅出现在顶层 facts 元素）。
         *
         * <p>该节点不做判断，只负责把事实引入工作内存并命名，
         * 供同级谓词与表达式引用。</p>
         */
        DEFINITION,

        /**
         * 全部成立
         */
        ALL,

        /**
         * 任一成立
         */
        ANY,

        /**
         * 取反
         */
        NOT,

        /**
         * 属性比较
         */
        COMPARE,

        /**
         * 绑定存在性
         */
        EXISTS,

        /**
         * 表达式（ASM 编译）
         */
        EXPR,

        /**
         * 决策：引用一张决策表并与期望结果比较
         */
        DECISION
    }

    /**
     * 节点类型
     */
    private final Kind kind;

    /**
     * 类型别名（KIND=DEFINITION）
     */
    private final String type;

    /**
     * 绑定名（KIND=DEFINITION）
     */
    private final String binding;

    /**
     * 属性路径（KIND=COMPARE）
     */
    private final String path;

    /**
     * 比较运算符（KIND=COMPARE）
     */
    private final String op;

    /**
     * 比较值（KIND=COMPARE）
     */
    private final Object value;

    /**
     * 绑定名（KIND=EXISTS）
     */
    private final String exists;

    /**
     * 全局变量名（KIND=COMPARE，path 为 global:xxx 时使用）
     */
    private final String global;

    /**
     * 表达式文本（KIND=EXPR）
     */
    private final String expr;

    /**
     * 子节点
     */
    private final List<RuleConditionSpec> children;

    /**
     * 构造节点。
     *
     * @param kind     节点类型
     * @param type     类型别名
     * @param binding  绑定名
     * @param path     属性路径
     * @param op       运算符
     * @param value    比较值
     * @param exists   存在性绑定名
     * @param global   全局变量名
     * @param expr     表达式文本
     * @param children 子节点
     */
    RuleConditionSpec(Kind kind, String type, String binding, String path, String op,
                      Object value, String exists, String global, String expr,
                      List<RuleConditionSpec> children) {
        this.kind = kind;
        this.type = type;
        this.binding = binding;
        this.path = path;
        this.op = op;
        this.value = value;
        this.exists = exists;
        this.global = global;
        this.expr = expr;
        this.children = children == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(children));
    }

    /**
     * 获取节点类型。
     *
     * @return 节点类型
     */
    public Kind kind() {
        return kind;
    }

    /**
     * 获取类型别名。
     *
     * @return 类型别名
     */
    public String type() {
        return type;
    }

    /**
     * 获取绑定名。
     *
     * @return 绑定名
     */
    public String binding() {
        return binding;
    }

    /**
     * 获取属性路径。
     *
     * @return 属性路径
     */
    public String path() {
        return path;
    }

    /**
     * 获取比较运算符。
     *
     * @return 比较运算符
     */
    public String op() {
        return op;
    }

    /**
     * 获取比较值。
     *
     * @return 比较值
     */
    public Object value() {
        return value;
    }

    /**
     * 获取存在性绑定的名字。
     *
     * @return 绑定名
     */
    public String exists() {
        return exists;
    }

    /**
     * 获取全局变量名。
     *
     * @return 全局变量名
     */
    public String global() {
        return global;
    }

    /**
     * 获取表达式文本。
     *
     * @return 表达式文本
     */
    public String expr() {
        return expr;
    }

    /**
     * 获取子节点。
     *
     * @return 子节点列表，只读
     */
    public List<RuleConditionSpec> children() {
        return children;
    }

    /**
     * 创建事实定义节点（{@link Kind#DEFINITION}）。
     *
     * <p>「定义」是本引擎对「声明一个事实类型并绑定到名字」的称呼：
     * 节点本身不做判断，只负责把某类事实引入工作内存并命名，
     * 供同级谓词与表达式引用。</p>
     *
     * @param type    类型别名
     * @param binding 绑定名
     * @param where   附加条件树，可为 null
     * @return 节点
     */
    public static RuleConditionSpec definition(String type, String binding, RuleConditionSpec where) {
        if (type == null || type.isBlank()) {
            throw new RuleException("事实定义缺少 type");
        }
        return new RuleConditionSpec(Kind.DEFINITION, type, binding, null, null, null,
                null, null, null, where == null ? List.of() : List.of(where));
    }

    /**
     * 创建 AND 节点。
     *
     * @param children 子节点
     * @return 节点
     */
    public static RuleConditionSpec all(List<RuleConditionSpec> children) {
        return new RuleConditionSpec(Kind.ALL, null, null, null, null, null,
                null, null, null, children);
    }

    /**
     * 创建 OR 节点。
     *
     * @param children 子节点
     * @return 节点
     */
    public static RuleConditionSpec any(List<RuleConditionSpec> children) {
        return new RuleConditionSpec(Kind.ANY, null, null, null, null, null,
                null, null, null, children);
    }

    /**
     * 创建 NOT 节点。
     *
     * @param child 子节点
     * @return 节点
     */
    public static RuleConditionSpec not(RuleConditionSpec child) {
        return new RuleConditionSpec(Kind.NOT, null, null, null, null, null,
                null, null, null, child == null ? List.of() : List.of(child));
    }

    /**
     * 创建属性比较节点。
     *
     * @param path  属性路径
     * @param op    运算符
     * @param value 比较值
     * @return 节点
     */
    public static RuleConditionSpec compare(String path, String op, Object value) {
        return compare(null, path, op, value);
    }

    /**
     * 创建带显式绑定名的属性比较节点。
     *
     * <p>{@code binding} 用于顶层 {@code where} 中指定比较目标：
     * {@code {"binding":"o","path":"amount","op":"GT","value":1}}。
     * 若节点位于某个 {@code facts[].where} 内部，则可省略，
     * 由外层事实的绑定名隐式确定。</p>
     *
     * @param binding 显式绑定名，可为 null
     * @param path    属性路径
     * @param op      运算符
     * @param value   比较值
     * @return 节点
     */
    public static RuleConditionSpec compare(String binding, String path, String op, Object value) {
        if (path == null || path.isBlank()) {
            throw new RuleException("属性比较缺少 path");
        }
        if (op == null || op.isBlank()) {
            throw new RuleException("属性比较缺少 op");
        }
        return new RuleConditionSpec(Kind.COMPARE, null, binding, path.trim(), op.trim(),
                value, null, null, null, List.of());
    }

    /**
     * 创建全局变量比较节点。
     *
     * @param global 全局变量名
     * @param op     运算符
     * @param value  比较值
     * @return 节点
     */
    public static RuleConditionSpec globalCompare(String global, String op, Object value) {
        if (global == null || global.isBlank()) {
            throw new RuleException("全局变量比较缺少 global");
        }
        return new RuleConditionSpec(Kind.COMPARE, null, null, null,
                op == null ? null : op.trim(), value, null, global.trim(), null, List.of());
    }

    /**
     * 创建存在性节点。
     *
     * @param binding 绑定名
     * @return 节点
     */
    public static RuleConditionSpec exists(String binding) {
        if (binding == null || binding.isBlank()) {
            throw new RuleException("存在性判断缺少绑定名");
        }
        return new RuleConditionSpec(Kind.EXISTS, null, null, null, null,
                null, binding.trim(), null, null, List.of());
    }

    /**
     * 创建表达式节点。
     *
     * @param expr 表达式文本
     * @return 节点
     */
    public static RuleConditionSpec expression(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new RuleException("表达式不能为空");
        }
        return new RuleConditionSpec(Kind.EXPR, null, null, null, null,
                null, null, null, expr.trim(), List.of());
    }

    /**
     * 创建决策节点。
     *
     * <p>复用既有字段承载三要素，避免给这个不可变记录再加字段：</p>
     * <ul>
     *   <li>{@code type} —— 决策表 id</li>
     *   <li>{@code op} —— 期望方式：{@code EQ}（默认）或 {@code IN}</li>
     *   <li>{@code value} —— 期望结果；{@code IN} 时为结果列表</li>
     * </ul>
     *
     * @param decisionId 决策表 id
     * @param expected   期望结果
     * @return 节点
     */
    public static RuleConditionSpec decision(String decisionId, Object expected) {
        return decision(decisionId, "EQ", expected);
    }

    /**
     * 创建决策节点。
     *
     * @param decisionId 决策表 id
     * @param mode       {@code EQ} 或 {@code IN}
     * @param expected   期望结果，或结果列表
     * @return 节点
     */
    public static RuleConditionSpec decision(String decisionId, String mode, Object expected) {
        if (decisionId == null || decisionId.isBlank()) {
            throw new RuleException("决策节点缺少 decision");
        }
        return new RuleConditionSpec(Kind.DECISION, decisionId, null, null,
                mode == null || mode.isBlank() ? "EQ" : mode.trim().toUpperCase(),
                expected, null, null, null, List.of());
    }

    @Override
    public String toString() {
        return switch (kind) {
            case DEFINITION -> "DEFINITION[" + type + " as " + binding + "]";
            case ALL -> "ALL" + children;
            case ANY -> "ANY" + children;
            case NOT -> "NOT" + children;
            case COMPARE -> global != null
                    ? "GLOBAL[" + global + " " + op + " " + value + "]"
                    : "CMP[" + path + " " + op + " " + value + "]";
            case EXISTS -> "EXISTS[" + exists + "]";
            case EXPR -> "EXPR[" + expr + "]";
            case DECISION -> "DECISION[" + type + " " + op + " " + value + "]";
        };
    }

    /**
     * 供解析器使用的参数容器。
     *
     * @return 空参数映射
     */
    static Map<String, Object> noArgs() {
        return Map.of();
    }
}
