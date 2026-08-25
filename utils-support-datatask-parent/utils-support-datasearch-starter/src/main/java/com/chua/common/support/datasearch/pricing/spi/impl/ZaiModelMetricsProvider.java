package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractModelMetricsProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * Z.AI 系列模型定价提供者。
 *
 * <p>包含 GLM-4.5、GLM-4.6 等模型通过 Z.AI 网关的定价。</p>
 *
 * <p>在线同步读取 Z.AI 官方定价文档表格（输入/输出单价位于非相邻列，
 * 按表头语义识别），失败时回退到 classpath 内置 JSON。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zai")
public class ZaiModelMetricsProvider extends AbstractModelMetricsProvider {

    /** Pricing_url */
    private static final String PRICING_URL = "https://docs.z.ai/guides/overview/pricing";

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
