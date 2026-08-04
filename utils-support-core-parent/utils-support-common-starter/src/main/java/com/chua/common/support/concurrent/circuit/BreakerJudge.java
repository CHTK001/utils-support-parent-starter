package com.chua.common.support.concurrent.circuit;

import com.chua.common.support.lang.ast.BTreeNode;

import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * 断路器判断器
 *
 * <p>负责 B-Tree 叶子节点（COMPARE / COLUMN / VALUE / FUNCTION）的具体求值。
 * 通过 SPI 或自定义实现注入 {@link CircuitBreaker}，实现判断逻辑的可替换。</p>
 *
 * <h3>判断时机</h3>
 * <pre>
 *   对于表达式 "age > 18 AND status == 'active'"
 *   B-Tree:       AND
 *                /    \
 *           (>)        (==)
 *          /   \      /    \
 *       age   18  status  'active'
 *
 *   判断器调用顺序（短路优化后）：
 *     1. judge((> age 18), context)    → true
 *     2. judge((== status 'active'), context) → true
 *     3. 最终结果: true
 * </pre>
 *
 * <h3>实现示例</h3>
 * <pre>{@code
 *   // 自定义判断器：日志记录每次判断
 *   BreakerJudge auditJudge = (node, ctx) -> {
 *       boolean result = new DefaultBreakerJudge().judge(node, ctx);
 *       log.info("节点 {} 判断结果 {}", node, result);
 *       return result;
 *   };
 *
 *   CircuitBreaker.builder()
 *       .expression("age > 18 AND status == 'active'")
 *       .context(ctx)
 *       .judge(auditJudge)
 *       .build();
 * }</pre>
 *
 * @author CH
 * @since 2026/07/28
 */
@NullUnmarked
@FunctionalInterface
public interface BreakerJudge {

    /**
     * 判断叶子节点
     *
     * @param node    当前评估的 B-Tree 节点
     * @param context 上下文参数 Map（由调用方传入）
     * @return true=通过, false=断路
     */
    boolean judge(BTreeNode node, Map<String, Object> context);
}