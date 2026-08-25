package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractModelMetricsProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * 小米大模型系列定价提供者。
 *
 * <p>包含 MiMo 等模型定价。</p>
 *
 * <p>在线同步读取小米 MiMo 开放平台按量计费文档表格（多输入列时优先取
 * "未命中缓存"单价），失败时回退到 classpath 内置 JSON。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("xiaomi")
public class XiaomiModelMetricsProvider extends AbstractModelMetricsProvider {

    /** Pricing_url */
    private static final String PRICING_URL = "https://mimo.mi.com/docs/zh-CN/price/pay-as-you-go";

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
