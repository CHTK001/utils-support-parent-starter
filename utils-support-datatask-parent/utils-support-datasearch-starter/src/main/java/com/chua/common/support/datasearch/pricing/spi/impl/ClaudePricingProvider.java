package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * Anthropic Claude 系列模型定价提供者。
 *
 * <p>包含 Sonnet、Opus、Haiku 等模型定价。</p>
 *
 * <p>官方定价文档为动态渲染，普通抓取失败时回退到 classpath 内置 JSON。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("claude")
public class ClaudePricingProvider extends AbstractPricingProvider {

    /** Pricing_url */
    private static final String PRICING_URL = "https://docs.anthropic.com/en/docs/about-claude/pricing";

    /**
     * 从官方定价页抓取在线定价数据。
     *
     * @return 模型定价列表，页面不可达或无有效表格时回退内置 JSON
     */
    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        return scrapeTablePricing(PRICING_URL, "USD");
    }
}