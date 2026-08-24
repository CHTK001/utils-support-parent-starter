package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * 七牛云系列模型定价提供者。
 *
 * <p>包含七牛云 AI 大模型推理提供的各模型定价。</p>
 *
 * <p>在线尝试读取 Token 计费说明文档，具体价格以模型广场页面为准，抓取失败时回退到 classpath 内置 JSON。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("qiniu")
public class QiniuPricingProvider extends AbstractPricingProvider {

    /** Pricing_url */
    private static final String PRICING_URL = "https://developer.qiniu.com/aitokenapi/12898/ai-token-api-pricing";

    /**
     * 从官方定价页抓取在线定价数据。
     *
     * @return 模型定价列表，页面不可达或无有效表格时回退内置 JSON
     */
    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        return scrapeTablePricing(PRICING_URL, "CNY");
    }
}