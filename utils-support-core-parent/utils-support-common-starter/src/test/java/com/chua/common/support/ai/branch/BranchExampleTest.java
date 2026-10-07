package com.chua.common.support.ai.branch;

import com.chua.common.support.ai.rule.Action;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * if-elseif-else 门面示例：对照传统写法演示分支链用法。
 *
 * <p>传统写法：</p>
 * <pre>{@code
 * String level;
 * if (risk >= 90) {
 *     level = "high";
 * } else if (risk >= 60) {
 *     level = "medium";
 * } else {
 *     level = "low";
 * }
 * }</pre>
 *
 * <p>规则写法见下方各用例。</p>
 */
class BranchExampleTest {

    /**
     * 示例一：基础 if-elseif-else 替代。
     *
     * <p>{@code when} 即 {@code if / else if}，{@code otherwise} 即 {@code else}；
     * 每段 {@code when...then...} 是独立 builder，独占语义——首个命中即停。</p>
     */
    @Test
    void exampleIfElseIfElse() {
        BranchResult result = Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .then(ctx -> ctx.set("level", "high"))
                .when(ctx -> ctx.intOf("risk", 0) >= 60)
                .then(ctx -> ctx.set("level", "medium"))
                .otherwise(ctx -> ctx.set("level", "low"))
                .execute(Map.of("risk", 95));

        assertEquals("high", result.value("level").orElseThrow());
        assertEquals("branch-1", result.hitId().orElseThrow());
    }

    /**
     * 示例二：命中原因（message）+ 固定分支标识（as）。
     *
     * <p>对应业务里的分级拦截：命中哪一级、为什么命中，
     * 直接从 {@link BranchResult#reason()} 取，不必再往上下文塞临时变量。</p>
     */
    @Test
    void exampleMessageAndFixedId() {
        BranchResult result = Branch.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .as("reject")
                .message("风险分 >= 90，直接拒绝")
                .then(ctx -> ctx.action(Action.REJECT))
                .when(ctx -> ctx.intOf("risk", 0) >= 60)
                .as("review")
                .message("风险分 >= 60，转人工复核")
                .then(ctx -> ctx.action(Action.REVIEW))
                .otherwise(ctx -> ctx.action(Action.PASS))
                .execute(Map.of("risk", 75));

        assertEquals("review", result.hitId().orElseThrow());
        assertEquals("风险分 >= 60，转人工复核", result.reason().orElseThrow());
        assertEquals(Action.REVIEW, result.action());
    }

    /**
     * 示例三：一段多个动作 + 多条件 OR。
     *
     * <p>同一段里可多次 {@code then} 按序执行；
     * 同一段里可多次 {@code when}（OR 关系），命中任意一个即进入该分支——
     * 对应 {@code if (a || b)}。</p>
     */
    @Test
    void exampleMultiThenAndOrConditions() {
        BranchResult result = Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .when(ctx -> "true".equals(ctx.stringOf("fraud")))
                .message("高风险或命中欺诈特征")
                .then(ctx -> ctx.action(Action.REJECT))
                .then(ctx -> ctx.set("notify", "sec-team"))
                .otherwise(ctx -> ctx.action(Action.PASS))
                .execute(Map.of("fraud", "true"));

        assertEquals(Action.REJECT, result.action());
        assertEquals("sec-team", result.value("notify").orElseThrow());
        assertTrue(result.anyHit());
    }

    /**
     * 示例四：表达式形态 + 只取门面复用。
     *
     * <p>{@code chain()} 把链终结为 {@link BranchChain} 后可缓存复用，
     * 多次 {@code execute} 互不干扰（门面无执行期状态）。</p>
     */
    @Test
    void exampleExpressionAndReuse() {
        BranchChain chain = Branches.when("risk >= 90")
                .message("表达式命中高风险")
                .then(ctx -> ctx.action(Action.REJECT))
                .otherwise(ctx -> ctx.action(Action.PASS))
                .chain();

        assertEquals(Action.REJECT, chain.execute(Map.of("risk", 95)).action());
        assertEquals(Action.PASS, chain.execute(Map.of("risk", 5)).action());
        assertEquals("表达式命中高风险", chain.execute(Map.of("risk", 99))
                .reason().orElseThrow());
    }

    /**
     * 示例五：行动结论用 {@link Action} 枚举，自定义值仍可写字符串。
     *
     * <p>枚举覆盖常用处置（放行/拒绝/复核/升级）；
     * 个性化结论传字符串，需要归一时 {@link Action#of(String)} 解析，
     * 识别不了返回 null（不算错误）。</p>
     */
    @Test
    void exampleActionEnum() {
        BranchResult hit = Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .then(ctx -> ctx.action(Action.ESCALATE))
                .otherwise(ctx -> ctx.action(Action.PASS))
                .execute(Map.of("risk", 95));

        assertEquals(Action.ESCALATE, hit.action());
        assertEquals("升级处理", Action.ESCALATE.description());
        assertEquals(Action.PASS, Action.of(" pass "));

        BranchResult custom = Branches.when(ctx -> true)
                .then(ctx -> ctx.action("SPECIAL_HANDLING"))
                .otherwise(ctx -> ctx.action(Action.PASS))
                .execute(Map.of());

        assertEquals("SPECIAL_HANDLING", custom.action());
        assertEquals(Action.REJECT, Action.of(String.valueOf(Action.REJECT)));
        assertNull(Action.of("whatever"));
    }
}
