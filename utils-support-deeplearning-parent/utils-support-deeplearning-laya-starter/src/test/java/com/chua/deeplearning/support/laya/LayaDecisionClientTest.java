package com.chua.deeplearning.support.laya;

import com.chua.common.support.ai.decision.DecisionBatch;
import com.chua.common.support.ai.decision.DecisionClient;
import com.chua.common.support.ai.decision.DecisionQuery;
import com.chua.common.support.ai.decision.DecisionSetting;
import com.chua.common.support.ai.decision.DecisionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pig4cloud.laya.Question;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Laya 问题映射与答案回填单测（纯本地，不加载模型）。
 */
class LayaDecisionClientTest {

    /**
     * JSON 映射器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    /**
     * 三型问题映射
     */
    void toQuestion() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("billing", "money");
        options.put("support", "bugs");
        Question choice = LayaDecisionClient.toQuestion(
                DecisionQuery.choice("department", "Which department?", options));
        assertEquals("choice", choice.type());
        assertEquals(2, choice.criteria().size());

        Question score = LayaDecisionClient.toQuestion(
                DecisionQuery.score("urgency", "How urgent?", List.of("low", "high")));
        assertEquals("score", score.type());
        assertTrue(score.criteria().isArray());

        Question noul = LayaDecisionClient.toQuestion(
                DecisionQuery.noul("refund", "Refund?"));
        assertEquals("noul", noul.type());
    }

    @Test
    /**
     * 答案回填三型
     */
    void toBatch() throws Exception {
        List<DecisionQuery> queries = List.of(
                DecisionQuery.choice("department", "Which department?",
                        Map.of("billing", "money", "support", "bugs")),
                DecisionQuery.score("urgency", "How urgent?", List.of("low", "high")),
                DecisionQuery.noul("refund", "Refund?"));
        ObjectNode result = (ObjectNode) MAPPER.readTree("{"
                + "\"answers\":{"
                + "\"department\":{\"type\":\"choice\",\"choice\":\"billing\","
                + "\"probabilities\":{\"billing\":0.9,\"support\":0.1},\"confidence\":0.8},"
                + "\"urgency\":{\"type\":\"score\",\"score\":0.9,"
                + "\"probabilities\":{\"0\":0.8,\"1\":0.2},\"confidence\":0.6},"
                + "\"refund\":{\"type\":\"noul\",\"noul\":0.7,\"confidence\":0.7}"
                + "}}");
        DecisionBatch batch = LayaDecisionClient.toBatch(queries, result);
        assertEquals(3, batch.size());
        assertEquals("billing", batch.decision("department").label());
        assertEquals(DecisionType.CHOICE, batch.decision("department").type());
        assertEquals("low", batch.decision("urgency").label());
        assertTrue(batch.decision("refund").isTrue());
        // 展平后规则层可直接读阈值变量
        assertEquals(0.7D, (Double) batch.flatten().get("refund.confidence"), 1e-9);
    }

    @Test
    /**
     * 缺答案抛异常不静默
     */
    void missingAnswer() throws Exception {
        List<DecisionQuery> queries = List.of(DecisionQuery.noul("refund", "Refund?"));
        ObjectNode result = (ObjectNode) MAPPER.readTree("{\"answers\":{}}");
        assertThrows(RuntimeException.class, () -> LayaDecisionClient.toBatch(queries, result));
    }

    @Test
    /**
     * SPI 接线：门面按 provider 名称解析到 Laya 实现
     */
    void spiResolve() {
        try (DecisionClient client = DecisionClient.create(DecisionSetting.builder()
                .provider("laya").modelPath("no-such-dir").build())) {
            assertInstanceOf(LayaDecisionClient.class, client);
        }
    }

    @Test
    /**
     * AI 规则接线：RuleType.AI 解析到 Laya 构造器并构造规则
     */
    void aiRuleResolve() {
        com.chua.common.support.ai.rule.RuleBuilder builder =
                com.chua.common.support.ai.rule.RuleType.AI.builder("refund-rule");
        assertInstanceOf(LayaAiRuleBuilder.class, builder);
        com.chua.common.support.ai.rule.AiRule rule = ((LayaAiRuleBuilder) builder)
                .query(DecisionQuery.noul("refund", "Refund?"), "true", 0.6D)
                .effect(com.chua.common.support.ai.rule.RuleEffect.ALLOW)
                .build();
        assertEquals("refund-rule", rule.id());
        assertEquals(1, rule.queries().size());
        assertEquals("laya", rule.provider());
    }
}
