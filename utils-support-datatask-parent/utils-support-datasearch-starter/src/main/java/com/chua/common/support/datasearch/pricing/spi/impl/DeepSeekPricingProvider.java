package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * DeepSeek 系列模型定价提供者。
 *
 * <p>包含 V3、R1 等模型定价。</p>
 *
 * <p>官方文档的价格区块由前端渲染，普通抓取失败时回退到 classpath 内置 JSON。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("deepseek")
public class DeepSeekPricingProvider extends AbstractPricingProvider {

    /** Pricing_url */
    private static final String PRICING_URL = "https://api-docs.deepseek.com/quick_start/pricing/";

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