package com.chua.common.support.datasearch.pricing;

import com.chua.common.support.ai.chat.ModelDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UsagePricingReconciler} 对账逻辑单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class UsagePricingReconcilerTest {

    private static ModelDefinition priced(String provider, String id, String in, String out) {
        return ModelDefinition.builder()
                .provider(provider)
                .id(id)
                .inputUnitPrice(in == null ? null : new BigDecimal(in))
                .outputUnitPrice(out == null ? null : new BigDecimal(out))
                .currency("USD")
                .build();
    }

    private static ModelDefinition capabilityOnly(String provider, String id) {
        return ModelDefinition.builder()
                .provider(provider)
                .id(id)
                .capabilities(List.of("chat", "reasoning"))
                .build();
    }

    @Test
    @DisplayName("有量有价不算缺价；折叠后可命中的模型也不算缺")
    void pricedAndFoldMatched() {
        var sources = List.of(List.of(priced("deepseek", "deepseek-v4-flash", "0.1", "0.4")));
        var usages = List.of(
                new UsagePricingReconciler.UsageEntry("opencode", "deepseek", "deepseek-v4-flash", 0.5D, true),
                new UsagePricingReconciler.UsageEntry("cc-switch", "some/gw", "deepseek-v4-flash", 0.2D, true));
        var report = UsagePricingReconciler.reconcile(usages, sources);
        assertTrue(report.unpricedModels().isEmpty(), "折叠命中不应判为缺价");
        assertEquals(2, report.totalUsages());
    }

    @Test
    @DisplayName("有用量但所有源都无价 → 计入缺价")
    void unpricedDetected() {
        var sources = List.of(List.of(priced("openai", "gpt-4o", "2.5", "10")));
        var usages = List.of(
                new UsagePricingReconciler.UsageEntry("opencode", "some", "mystery-model", 0D, true),
                new UsagePricingReconciler.UsageEntry("opencode", "some", "mystery-model", 0D, true));
        var report = UsagePricingReconciler.reconcile(usages, sources);
        assertEquals(1, report.unpricedModels().size());
        assertEquals(2, report.unpricedModels().get("some:mystery-model"));
    }

    @Test
    @DisplayName("仅能力的条目（无价格）也算缺价，避免计费落 0 却被忽略")
    void capabilityOnlyCountsAsUnpriced() {
        var sources = List.of(List.of(capabilityOnly("vercel", "gpt-5.6-luna")));
        var usages = List.of(
                new UsagePricingReconciler.UsageEntry("opencode", "openai", "gpt-5.6-luna", 0D, true));
        var report = UsagePricingReconciler.reconcile(usages, sources);
        assertEquals(1, report.unpricedModels().size(), "有能力但无价仍应提示缺价");
    }

    @Test
    @DisplayName("外部零费用用量按来源汇总；非外部不计入")
    void zeroCostGrouping() {
        var sources = List.of(List.of(priced("openai", "gpt-4o", "2.5", "10")));
        var usages = List.of(
                new UsagePricingReconciler.UsageEntry("opencode", "openai", "gpt-4o", 0D, true),
                new UsagePricingReconciler.UsageEntry("opencode", "openai", "gpt-4o", 0D, true),
                new UsagePricingReconciler.UsageEntry("chat", "openai", "gpt-4o", 0D, false));
        var report = UsagePricingReconciler.reconcile(usages, sources);
        assertEquals(2, report.zeroCostGroups().get("opencode"));
        assertFalse(report.zeroCostGroups().containsKey("chat"), "非外部来源不计零费用");
    }

    @Test
    @DisplayName("同厂商同模型多条有价 → 报重复定价")
    void duplicatePricingDetected() {
        var sources = List.of(
                List.of(priced("openai", "gpt-5.6-luna", "0.2", "1.2")),
                List.of(priced("openai", "gpt-5.6-luna", "0.25", "1.5")),
                List.of(priced("anthropic", "claude-opus-5", "5", "25")));
        var usages = List.<UsagePricingReconciler.UsageEntry>of();
        var report = UsagePricingReconciler.reconcile(usages, sources);
        assertEquals(1, report.duplicatePricings().size());
        assertEquals(2, report.duplicatePricings().get("openai:gpt-5.6-luna"));
    }

    @Test
    @DisplayName("有价但无量 → 闲置定价；命中过的不算闲置")
    void idlePricings() {
        var sources = List.of(List.of(
                priced("openai", "gpt-4o", "2.5", "10"),
                priced("openai", "gpt-4o-mini", "0.15", "0.6")));
        var usages = List.of(
                new UsagePricingReconciler.UsageEntry("opencode", "openai", "gpt-4o", 1D, true));
        var report = UsagePricingReconciler.reconcile(usages, sources);
        assertTrue(report.idlePricings().contains("openai:gpt-4o-mini"));
        assertFalse(report.idlePricings().contains("openai:gpt-4o"));
    }

    @Test
    @DisplayName("报告可渲染且含关键小节")
    void render() {
        var sources = List.of(List.of(priced("openai", "gpt-4o", "2.5", "10")));
        var usages = List.of(
                new UsagePricingReconciler.UsageEntry("opencode", "x", "nope", 0D, true));
        var text = UsagePricingReconciler.reconcile(usages, sources).render();
        assertTrue(text.contains("对账报告"));
        assertTrue(text.contains("缺价模型"));
        assertTrue(text.contains("nope"));
    }
}
