package com.chua.common.support.ai.branch;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分支门面冒烟测试：独占语义 + else 兜底 + 不可达校验。
 */
class BranchChainTest {

    @Test
    void firstHitWins() {
        BranchChain chain = BranchChain.of(
                Branch.definition("high").when(ctx -> ctx.intOf("risk", 0) >= 90)
                        .message("高风险拦截").build(),
                Branch.definition("medium").when(ctx -> ctx.intOf("risk", 0) >= 60)
                        .message("中风险复核").build(),
                Branch.definition("fallback").build());
        BranchResult result = chain.execute(Map.of("risk", 95));
        assertEquals("high", result.hitId().orElseThrow());
        assertEquals("高风险拦截", result.reason().orElseThrow());
    }

    @Test
    void fallbackWhenNothingHit() {
        BranchChain chain = BranchChain.of(
                Branch.definition("high").when(ctx -> ctx.intOf("risk", 0) >= 90).build(),
                Branch.definition("fallback").build());
        BranchResult result = chain.execute(Map.of("risk", 10));
        assertEquals("fallback", result.hitId().orElseThrow());
    }

    @Test
    void unconditionalOnlyLast() {
        assertThrows(IllegalArgumentException.class, () -> BranchChain.of(
                Branch.definition("fallback").build(),
                Branch.definition("high").when(ctx -> true).build()));
    }

    @Test
    void builderSingleUse() {
        BranchBuilder builder = Branch.definition("once");
        builder.build();
        assertThrows(com.chua.common.support.ai.rule.RuleException.class, builder::build);
        assertThrows(com.chua.common.support.ai.rule.RuleException.class,
                () -> builder.message("x"));
    }

    @Test
    void fluentChainEachWhenThenSeparate() {
        BranchResult result = Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .message("高风险拦截")
                .then(ctx -> ctx.set("level", "high"))
                .when(ctx -> ctx.intOf("risk", 0) >= 60)
                .message("中风险复核")
                .then(ctx -> ctx.set("level", "medium"))
                .otherwise(ctx -> ctx.set("level", "low"))
                .execute(Map.of("risk", 95));
        assertEquals("branch-1", result.hitId().orElseThrow());
        assertEquals("高风险拦截", result.reason().orElseThrow());
        assertEquals("high", result.value("level").orElseThrow());
    }

    @Test
    void fluentChainSecondBranch() {
        BranchResult result = Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .then(ctx -> ctx.set("level", "high"))
                .when(ctx -> ctx.intOf("risk", 0) >= 60)
                .then(ctx -> ctx.set("level", "medium"))
                .otherwise(ctx -> ctx.set("level", "low"))
                .execute(Map.of("risk", 70));
        assertEquals("branch-2", result.hitId().orElseThrow());
        assertEquals("medium", result.value("level").orElseThrow());
    }

    @Test
    void fluentChainFallback() {
        BranchResult result = Branches.when(ctx -> ctx.intOf("risk", 0) >= 90)
                .then(ctx -> ctx.set("level", "high"))
                .otherwise(ctx -> ctx.set("level", "low"))
                .execute(Map.of("risk", 10));
        assertEquals("branch-2", result.hitId().orElseThrow());
        assertEquals("low", result.value("level").orElseThrow());
    }

    @Test
    void eachWhenThenIsSeparateBuilder() {
        BranchWhenBuilder first = Branches.when(ctx -> true);
        BranchThenBuilder then = first.then(ctx -> ctx.set("a", 1));
        BranchWhenBuilder second = then.when(ctx -> false);
        // 每段都是独立 builder 实例，共享状态由内部承载
        assertNotSame(first, second);
        assertNotSame(then, second);
        // 旧 builder 仍可继续终结链，状态共享不断链
        BranchResult result = second.execute(Map.of("k", "v"));
        assertEquals("branch-1", result.hitId().orElseThrow());
        assertEquals(1, result.value("a").orElseThrow());
    }

    @Test
    void builderRejectsWriteAfterEnd() {
        BranchWhenBuilder first = Branches.when(ctx -> true);
        first.chain();
        assertThrows(com.chua.common.support.ai.rule.RuleException.class,
                () -> first.when(ctx -> false));
    }
}
