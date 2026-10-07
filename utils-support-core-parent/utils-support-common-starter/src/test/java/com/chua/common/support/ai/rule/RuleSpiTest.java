package com.chua.common.support.ai.rule;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则接口化冒烟测试：SPI 装配 + 单构造器单规则 + 规则集类型隔离。
 */
class RuleSpiTest {

    @Test
    void nativeBuilderBySpi() {
        NativeRule rule = Rule.definition("demo")
                .when(ctx -> true)
                .build();
        assertEquals(RuleType.NATIVE, rule.type());
        assertEquals("demo", rule.id());
    }

    @Test
    void noArgAndEnumBuilders() {
        assertNotNull(Rule.builder());
        assertNotNull(Rule.builder(RuleType.NATIVE));
        assertNotNull(Rule.builder(RuleType.NATIVE, "demo"));
    }

    @Test
    void builderSingleUse() {
        NativeRuleBuilder builder = Rule.definition("once");
        builder.build();
        assertThrows(RuleException.class, builder::build);
        assertThrows(RuleException.class, () -> builder.salience(1));
    }

    @Test
    void ruleSetRejectsMixedType() {
        NativeRule nativeRule = Rule.definition("n1").build();
        AiRule aiRule = new AiRule() {
            @Override
            public List<AiRuleQuery> queries() {
                return List.of();
            }

            @Override
            public boolean hit(Map<String, String> answers, Map<String, Object> flattened) {
                return false;
            }

            @Override
            public String id() {
                return "a1";
            }

            @Override
            public String name() {
                return "a1";
            }

            @Override
            public String message() {
                return null;
            }

            @Override
            public int salience() {
                return 0;
            }

            @Override
            public RuleEffect effect() {
                return RuleEffect.NONE;
            }

            @Override
            public List<java.util.function.Predicate<RuleContext>> conditions() {
                return List.of();
            }

            @Override
            public List<java.util.function.Consumer<RuleContext>> actions() {
                return List.of();
            }

            @Override
            public String target() {
                return null;
            }

            @Override
            public Map<String, Object> variables() {
                return Map.of();
            }

            @Override
            public boolean matched(RuleContext context) {
                return false;
            }

            @Override
            public void apply(RuleContext context) {
            }

            @Override
            public boolean hasActions() {
                return false;
            }

            @Override
            public boolean hasVariables() {
                return false;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new RuleSet(List.of(nativeRule, aiRule)));
    }

    @Test
    void nativeExecute() {
        NativeRule rule = Rule.definition("hit")
                .when(ctx -> true)
                .then(ctx -> ctx.set("k", "v"))
                .build();
        RuleResult result = RuleExecutor.of(RuleSet.of(rule)).execute(Map.of());
        assertEquals("v", result.vars().get("k"));
    }

    @Test
    void messageCollected() {
        NativeRule rule = Rule.definition("deny")
                .message("命中反欺诈")
                .effect(RuleEffect.DENY)
                .when(ctx -> true)
                .build();
        RuleResult result = RuleExecutor.of(RuleSet.of(rule)).execute(Map.of());
        assertEquals("命中反欺诈", result.message("deny").orElseThrow());
        assertEquals("命中反欺诈", result.messages().get("deny"));
    }

    @Test
    void contentTypedRead() {
        NativeRule rule = Rule.definition("typed")
                .when(ctx -> ctx.intOf("risk", 0) >= 90)
                .when(ctx -> "true".equals(ctx.stringOf("fraud")))
                .build();
        RuleResult result = RuleExecutor.of(RuleSet.of(rule))
                .execute(Map.of("risk", "95", "fraud", true));
        assertTrue(result.anyHit());
    }

    @Test
    void descriptionDeclared() {
        NativeRule rule = Rule.definition("deny")
                .description("反欺诈拦截：风控分>=90")
                .when(ctx -> true)
                .build();
        assertEquals("反欺诈拦截：风控分>=90", rule.description());
    }
}
