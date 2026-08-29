package com.chua.common.support.datasearch.exchange.spi;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 汇率提供者扩展接口，用于获取货币间实时汇率（独立于模型定价体系）。
 *
 * <p>各实现通过 ServiceProvider 加载，主要面向国内用户将 USD 价格换算为
 * RMB（CNY）等本币口径。实现应提供 {@link #getRate(String, String)} 任意币种
 * 双向换算，以及 {@link #getRates(String)} 基准币种全量汇率。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ExchangeRateProvider {

    /**
     * 当前汇率数据源名称。
     *
     * @return 数据源标识
     */
    String name();

    /**
     * 获取指定币种汇率（1 单位 from = ? 单位 to）。
     *
     * @param from 源币种（ISO 4217，如 USD、CNY）
     * @param to   目标币种（ISO 4217，如 USD、CNY）
     * @return 汇率；数据源不可达或币种不存在时返回 null
     */
    BigDecimal getRate(String from, String to);

    /**
     * 获取以指定币种为基准的全量汇率表。
     *
     * @param base 基准币种（ISO 4217）
     * @return 币种 -> 汇率（1 单位 base 可兑换的数量）；失败返回空表
     */
    Map<String, Double> getRates(String base);
}
