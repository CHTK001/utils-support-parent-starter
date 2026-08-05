package com.chua.common.support.ai.chat.usage;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.chat.pricing.ModelPricingProvider;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.ThreadUtils;
import lombok.Data;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * AI 用量记录实体 — 通过 Engine ORM 属性字段持久化每次调用的 Token/费用/延迟。
 *
 * <p>配合链式 Builder 使用，支持按任意字段查询和 Engine 持久化。
 * 无需 JSON，所有字段通过 Engine Lambda 操作：
 * <pre>{@code
 *   // 链式创建并保存
 *   AiUsageRecord record = AiUsageRecord.create()
 *       .provider("openai").model("gpt-4")
 *       .inputTokens(1200).outputTokens(800).totalTokens(2000)
 *       .cost(new BigDecimal("0.018"), "USD")
 *       .duration(1200L)
 *       .save(engine);
 *
 *   // 从 AiUsage 转换
 *   AiUsageRecord record = AiUsageRecord.from(usage).save(engine);
 *
 *   // 查询某服务商本月用量
 *   List<AiUsageRecord> records = engine.query(AiUsageRecord.class)
 *       .eq("provider", "openai")
 *       .gt("createdAt", monthStart)
 *       .orderByDesc("createdAt")
 *       .list();
 *
 *   // 统计总消耗
 *   long totalTokens = records.stream()
 *       .mapToLong(AiUsageRecord::getTotalTokens).sum();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class AiUsageRecord {

    /** 主键 */
    /**
     * 标识
     */
    private Long id;

    // ==================== Token 用量 ====================

    /** 输入 Token 数 */
    private Integer inputTokens;

    /** 输出 Token 数 */
    private Integer outputTokens;

    /** 总 Token 数 */
    private Integer totalTokens;

    /** 缓存命中 Token 数 */
    private Integer cacheTokens;

    /** 推理 Token 数 */
    private Integer reasoningTokens;

    // ==================== 费用信息 ====================

    /** 输入费用 */
    private BigDecimal inputCost;

    /** 输出费用 */
    private BigDecimal outputCost;

    /** 总费用 */
    private BigDecimal totalCost;

    /** 货币（USD / CNY） */
    private String currency;

    // ==================== 模型标识 ====================

    /** 服务商名称 */
    /**
     * 提供方标识
     */
    private String provider;

    /** 模型名称 */
    /**
     * 模型名称
     */
    private String model;

    // ==================== 性能指标 ====================

    /** 总耗时（毫秒） */
    private Long durationMillis;

    /** 首字耗时（毫秒） */
    private Long firstTokenLatencyMillis;

    /** 停止原因 */
    private String finishReason;

    // ==================== 基础信息 ====================

    /** 请求时间戳 */
    /**
     * 创建时间
     */
    private Long createdAt;

    // ======================== 链式 Builder ========================

    /**
     * 创建链式 Builder
     *
     * @return Builder
     */
    public static Builder create() {
        return new Builder();
    }

    /**
     * 从 {@link AiUsage} 创建记录
     *
     * @param usage AI 用量（null 时返回 null）
     * @return 用量记录（未保存），usage 为 null 则返回 null
     */
    public static AiUsageRecord from(AiUsage usage) {
        if (usage == null) return null;
        AiUsageRecord record = new AiUsageRecord();
        record.inputTokens = usage.getInputTokens();
        record.outputTokens = usage.getOutputTokens();
        record.totalTokens = usage.getTotalTokens();
        record.cacheTokens = usage.getCacheTokens();
        record.reasoningTokens = usage.getReasoningTokens();
        record.inputCost = usage.getInputCost();
        record.outputCost = usage.getOutputCost();
        record.totalCost = usage.getTotalCost();
        record.currency = usage.getCurrency();
        record.provider = usage.getProvider();
        record.model = usage.getModel();
        record.durationMillis = usage.getDurationMillis();
        record.firstTokenLatencyMillis = usage.getFirstTokenLatencyMillis();
        record.finishReason = usage.getFinishReason();
        record.createdAt = usage.getStartTime() != null ? usage.getStartTime() : System.currentTimeMillis();

        // 如果缺失单价，尝试从 PricingProvider 补齐
        if (record.provider != null && record.model != null) {
            enrichPricing(record);
        }

        return record;
    }

    private static void enrichPricing(AiUsageRecord record) {
        try {
            ModelPricingProvider provider = ServiceProvider.of(ModelPricingProvider.class)
                    .getExtension(record.provider);
            if (provider == null) return;
            ModelDefinition pricing = provider.getModelPricing(record.provider, record.model);
            if (pricing == null) return;
            if (record.inputCost == null && pricing.getInputUnitPrice() != null && record.inputTokens != null) {
                record.inputCost = pricing.getInputUnitPrice()
                        .multiply(BigDecimal.valueOf(record.inputTokens))
                        .divide(BigDecimal.valueOf(1_000_000), 10, BigDecimal.ROUND_HALF_UP);
            }
            if (record.outputCost == null && pricing.getOutputUnitPrice() != null && record.outputTokens != null) {
                record.outputCost = pricing.getOutputUnitPrice()
                        .multiply(BigDecimal.valueOf(record.outputTokens))
                        .divide(BigDecimal.valueOf(1_000_000), 10, BigDecimal.ROUND_HALF_UP);
            }
            if (record.currency == null && pricing.getCurrency() != null) {
                record.currency = pricing.getCurrency();
            }
            if (record.totalCost == null && record.inputCost != null && record.outputCost != null) {
                record.totalCost = record.inputCost.add(record.outputCost);
            }
        } catch (Exception e) {
            // 忽略，不影响主流程
        }
    }

    /** 转换为 {@link AiUsage} */
    public AiUsage toAiUsage() {
        return AiUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .cacheTokens(cacheTokens)
                .reasoningTokens(reasoningTokens)
                .inputCost(inputCost)
                .outputCost(outputCost)
                .totalCost(totalCost)
                .currency(currency)
                .provider(provider)
                .model(model)
                .durationMillis(durationMillis)
                .firstTokenLatencyMillis(firstTokenLatencyMillis)
                .finishReason(finishReason)
                .estimated(false)
                .build();
    }

    // ======================== 持久化 ========================

    // ======================== 同步持久化 ========================

    /**
     * 通过 Engine ORM 保存或更新本条记录
     *
     * @param engine Engine 实例
     * @return 自身（支持链式）
     */
    public AiUsageRecord save(Engine engine) {
        if (engine == null) return this;
        doSave(engine);
        return this;
    }

    /**
     * 通过 Engine ORM 异步保存本条记录
     *
     * <p>使用全局虚拟线程池执行，不阻塞调用线程。
     * 调用后不应再修改此记录对象，避免与后台线程的竞态条件。
     * 适用于 {@code AggregateChatClient} 等高性能场景的用量记录。
     *
     * @param engine Engine 实例
     * @return CompletableFuture，完成时返回自身
     */
    public CompletableFuture<AiUsageRecord> asyncSave(Engine engine) {
        if (engine == null) return CompletableFuture.completedFuture(this);
        return CompletableFuture.supplyAsync(() -> {
            doSave(engine);
            return this;
        }, ThreadUtils.newStaticThreadPool());
    }

    private void doSave(Engine engine) {
        if (createdAt == null) {
            createdAt = System.currentTimeMillis();
        }
        engine.update(AiUsageRecord.class).saveOrUpdate(this);
    }

    /**
     * 通过 Engine ORM 删除本条记录
     *
     * @param engine Engine 实例
     */
    public void delete(Engine engine) {
        if (engine == null || id == null) return;
        engine.delete(AiUsageRecord.class).eq("id", id).remove();
    }

    // ======================== Builder 实现 ========================

    /**
     * 链式 Builder — 以 fluent API 构建 {@link AiUsageRecord}。
     *
     * <p>使用示例：
     * <pre>{@code
     *   AiUsageRecord record = AiUsageRecord.create()
     *       .provider("openai").model("gpt-4")
     *       .inputTokens(100).outputTokens(200).totalTokens(300)
     *       .duration(1500L)
     *       .save(engine);
     * }</pre>
     */
    public static class Builder {

        private final AiUsageRecord record = new AiUsageRecord();

        Builder() {
            record.createdAt = System.currentTimeMillis();
        }

        // ---- Token 用量 ----

        public Builder inputTokens(int inputTokens) {
            record.inputTokens = inputTokens;
            return this;
        }

        public Builder outputTokens(int outputTokens) {
            record.outputTokens = outputTokens;
            return this;
        }

        public Builder totalTokens(int totalTokens) {
            record.totalTokens = totalTokens;
            return this;
        }

        public Builder tokens(int input, int output, int total) {
            record.inputTokens = input;
            record.outputTokens = output;
            record.totalTokens = total;
            return this;
        }

        public Builder cacheTokens(int cacheTokens) {
            record.cacheTokens = cacheTokens;
            return this;
        }

        public Builder reasoningTokens(int reasoningTokens) {
            record.reasoningTokens = reasoningTokens;
            return this;
        }

        // ---- 费用 ----

        public Builder inputCost(BigDecimal inputCost) {
            record.inputCost = inputCost;
            return this;
        }

        public Builder outputCost(BigDecimal outputCost) {
            record.outputCost = outputCost;
            return this;
        }

        public Builder totalCost(BigDecimal totalCost) {
            record.totalCost = totalCost;
            return this;
        }

        /** 同时设置总费用和货币 */
        public Builder cost(BigDecimal totalCost, String currency) {
            record.totalCost = totalCost;
            record.currency = currency;
            return this;
        }

        public Builder currency(String currency) {
            record.currency = currency;
            return this;
        }

        // ---- 模型标识 ----

        public Builder provider(String provider) {
            record.provider = provider;
            return this;
        }

        public Builder model(String model) {
            record.model = model;
            return this;
        }

        /** 同时设置服务商和模型 */
        public Builder providerAndModel(String provider, String model) {
            record.provider = provider;
            record.model = model;
            return this;
        }

        // ---- 性能 ----

        public Builder durationMillis(Long durationMillis) {
            record.durationMillis = durationMillis;
            return this;
        }

        /** 设置总耗时 */
        public Builder duration(Long durationMillis) {
            record.durationMillis = durationMillis;
            return this;
        }

        public Builder firstTokenLatencyMillis(Long firstTokenLatencyMillis) {
            record.firstTokenLatencyMillis = firstTokenLatencyMillis;
            return this;
        }

        public Builder finishReason(String finishReason) {
            record.finishReason = finishReason;
            return this;
        }

        // ---- 构建与持久化 ----

        /** 构建 {@link AiUsageRecord} */
        public AiUsageRecord build() {
            if (record.totalTokens == null && record.inputTokens != null && record.outputTokens != null) {
                record.totalTokens = record.inputTokens + record.outputTokens;
            }
            if (record.totalCost == null && record.inputCost != null && record.outputCost != null) {
                record.totalCost = record.inputCost.add(record.outputCost);
            }
            return record;
        }

        /** 构建并通过 Engine 保存（同步） */
        public AiUsageRecord save(Engine engine) {
            return build().save(engine);
        }

        /** 构建并通过 Engine 异步保存 */
        public CompletableFuture<AiUsageRecord> asyncSave(Engine engine) {
            return build().asyncSave(engine);
        }
    }
}