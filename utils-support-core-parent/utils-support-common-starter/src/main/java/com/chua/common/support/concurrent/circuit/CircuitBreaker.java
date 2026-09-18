package com.chua.common.support.concurrent.circuit;

import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;

import java.util.Map;

/**
* 断路器
*
* <p>基于 B-Tree 表达式评估的条件断路器。通过解析表达式为 B-Tree，
* 遍历节点并由 {@link BreakerJudge} 判断是否通过（true）或断路（false）。</p>
*
* <h3>核心特性</h3>
* <ul>
*   <li>表达式支持多种类型（通过 SPI ExpressionParser 自动识别）</li>
*   <li>支持默认判断器 {@link DefaultBreakerJudge} 自动注入</li>
*   <li>判断器可自定义替换（实现 {@link BreakerJudge}）</li>
*   <li>短路优化：AND 左边失败直接断路，OR 左边成功直接通过</li>
*   <li>异常保护：judge 抛异常时 evaluate 返回 false（保守断路）</li>
*   <li>典型场景：业务规则拦截 / 动态路由 / 特性开关</li>
* </ul>
*
* <h3>短路优化示意</h3>
* <pre>
*   "A && B && C"  → A=false 时，B 和 C 不执行（AND 短路）
*   "A || B || C"  → A=true  时，B 和 C 不执行（OR 短路）
* </pre>
*
* <h3>使用示例</h3>
* <pre>{@code
*   // 创建断路器（使用默认判断器）
*   CircuitBreaker breaker = CircuitBreaker.builder()
*       .expression("age > 18 AND status == 'active'")
*       .context(Map.of("age", 25, "status", "active"))
*       .build();
*
*   boolean result = breaker.evaluate();
*
*   // 自定义判断器
*   BreakerJudge auditJudge = (node, ctx) -> {
*       boolean result = new DefaultBreakerJudge().judge(node, ctx);
*       // 审计日志...
*       return result;
*   };
*
*   CircuitBreaker.builder()
*       .expression(...)
*       .context(ctx)
*       .judge(auditJudge)
*       .build();
*
*   // 便捷方法（每次新建）
*   boolean allow = CircuitBreaker.evaluate("age > 18", Map.of("age", 25));
* }</pre>
*
* @author CH
* @since 2026/07/16
 */
@Slf4j
public class CircuitBreaker {

    /**
    * 默认判断器（共享单例，避免重复创建）
    */
    private static final BreakerJudge DEFAULT_JUDGE = new DefaultBreakerJudge();

    /**
    * 表达式文本
    */
    private final String expression;

    /**
    * B-Tree 根节点
    */
    @Getter
    /** Tree */
    private final BTreeNode tree;

    /**
    * 上下文参数
    */
    private final Map<String, Object> context;

    /**
    * 判断器（叶子节点求值）
    */
    private final BreakerJudge judge;

    /**
    * 表达式类型（默认 "expr"）
    */
    private final String expressionType;

    /**
    * 创建 CircuitBreaker 实例
    * @param builder builder
    */
    private CircuitBreaker(Builder builder) {
        this.expression = builder.expression;
        this.context = builder.context;
        this.judge = builder.judge;
        this.expressionType = builder.expressionType;

        // 通过 SPI 解析表达式为 B-Tree
        ExpressionParser parser = ServiceProvider.of(ExpressionParser.class)
                .getExtension(builder.expressionType);
        this.tree = parser.parse(expression);
    }

    /**
    * 创建 Builder
    *
    * @return 新的 Builder
    */
    public static Builder builder() {
        return new Builder();
    }

    /**
    * 便捷评估方法：每次新建断路器并立即求值。
    *
    * <p>适合一次性规则判断场景。如需复用（多次 evaluate），请使用 {@link #builder()}。</p>
    *
    * @param expression 表达式文本
    * @param context    上下文参数
    * @return true=通过, false=断路
    */
    public static boolean evaluate(String expression, Map<String, Object> context) {
        return builder()
                .expression(expression)
                .context(context)
                .build()
                .evaluate();
    }

    /**
    * 执行断路评估
    *
    * <p>遍历 B-Tree，对每个叶子节点调用判断器求值。
    * 支持短路优化：
    * <ul>
    *   <li>AND：左边 false 直接返回 false（不评估右边）</li>
    *   <li>OR：左边 true 直接返回 true（不评估右边）</li>
    *   <li>NOT：反转子节点结果</li>
    * </ul>
    *
    * <p>异常保护：判断器抛 RuntimeException 时返回 false（保守断路），异常信息打印到 stderr。
    * 这保证业务规则拦截不会因为 judge 实现 bug 导致误放行。</p>
    *
    * @return true=通过, false=断路
    */
    public boolean evaluate() {
        try {
            return evaluateNode(tree);
        } catch (RuntimeException e) {
            log.error("[CircuitBreaker] evaluate failed for expression '{}': {}", expression, e.getMessage(), e);
            return false;
        }
    }

    /**
    * 递归评估节点（支持短路优化）
    * @param node 节点，不允许为 null
    * @return 是否成功（true 表示成功）
    */
    private boolean evaluateNode(BTreeNode node) {
        if (node == null) {
            return true;
        }
        return switch (node.getType()) {
            case LOGIC -> evaluateLogic(node);
            case NOT -> !evaluateNode(node.getRight());
            case COMPARE, COLUMN, VALUE, FUNCTION -> judge.judge(node, context);
            case RAW -> true;
        };
    }

    /**
    * 评估逻辑运算节点（短路优化）
    *
    * <p>AND：左边 false → 直接返回 false，不评估右边
    * OR：左边 true → 直接返回 true，不评估右边
    * @param node 节点，不允许为 null
    * @return 是否成功（true 表示成功）
    */
    private boolean evaluateLogic(BTreeNode node) {
        boolean leftResult = evaluateNode(node.getLeft());

        if (node.isAnd()) {
            if (!leftResult) {
                return false;
            }
            return evaluateNode(node.getRight());
        }

        if (node.isOr()) {
            if (leftResult) {
                return true;
            }
            return evaluateNode(node.getRight());
        }

        // 未知逻辑运算符，两边都评估
        boolean rightResult = evaluateNode(node.getRight());
        return leftResult && rightResult;
    }

    /**
    * 断路器构建器
    */
    public static class Builder {

        /** 熔断表达式 */
        private String expression;
        /** 上下文对象 */
        private Map<String, Object> context;
        /** 熔断判定器 */
        private BreakerJudge judge;
        /** 表达式类型 */
        private String expressionType = "expr";

        Builder() {
        }

        /**
        * 设置表达式
        *
        * @param expression 表达式文本
        * @return 当前 Builder
        */
        public Builder expression(String expression) {
            this.expression = expression == null ? null : expression.trim();
            return this;
        }

        /**
        * 设置表达式类型
        *
        * <p>默认 "expr"（通用表达式）。可选值由 SPI ExpressionParser 实现决定：
        * <ul>
        *   <li>"sql" — SQL WHERE 条件</li>
        *   <li>"expr" — 通用表达式（默认）</li>
        *   <li>"lucene" — Lucene 查询</li>
        *   <li>"cypher" — Cypher 图查询</li>
        * </ul>
        *
        * @param expressionType 表达式类型标识
        * @return 当前 Builder
        */
        public Builder expressionType(String expressionType) {
            this.expressionType = expressionType;
            return this;
        }

        /**
        * 设置上下文参数（必填）
        *
        * <p>判断器通过此 Map 获取列对应的值进行比较。
        *
        * @param context 上下文参数 Map
        * @return 当前 Builder
        */
        public Builder context(Map<String, Object> context) {
            this.context = context;
            return this;
        }

        /**
        * 设置判断器（可选）。
        *
        * <p>未设置时 build() 自动注入 {@link DefaultBreakerJudge}，
        * 默认判断器已支持全部规则模式（expr/sql/lucene/cypher）。
        * 可通过实现自定义 {@link BreakerJudge} 替换判断逻辑。</p>
        *
        * @param judge 判断器实例
        * @return 当前 Builder
        */
        public Builder judge(BreakerJudge judge) {
            this.judge = judge;
            return this;
        }

        /**
        * 构建断路器
        *
        * @return CircuitBreaker 实例
        * @throws IllegalArgumentException 缺少必填参数时抛出
        */
        public CircuitBreaker build() {
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("表达式不能为空");
            }
            if (context == null) {
                throw new IllegalArgumentException("上下文 Map 不能为空");
            }
            if (judge == null) {
                judge = DEFAULT_JUDGE;
            }
            return new CircuitBreaker(this);
        }
    }
}
