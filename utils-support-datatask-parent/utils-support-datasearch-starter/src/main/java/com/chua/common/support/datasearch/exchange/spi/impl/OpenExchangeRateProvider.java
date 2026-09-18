package com.chua.common.support.datasearch.exchange.spi.impl;

import com.chua.common.support.datasearch.exchange.api.ExchangeRateApi;
import com.chua.common.support.datasearch.exchange.model.ExchangeRateResponse;
import com.chua.common.support.datasearch.exchange.spi.ExchangeRateProvider;
import com.chua.common.support.network.invoker.InvokerFactory;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Map;

/**
* 打开.er-api.com 汇率数据源实现。
*
* <p>通过 {@link ExchangeRateApi}（HttpInvoker 实体查询）调用免费公开接口
* {@code https://open.er-api.com/v6/latest/USD}（无需 key，约 166 个币种）。</p>
*
* <p>以 USD 为基准抓取全量汇率并做 6 小时内存缓存（惰性刷新，不内置定时任务）。
* 任意币对换算 {@code from -> to = usdRate(to) / usdRate(from)}，
* 主要面向国内用户将 USD 价格换算为 CNY。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("open-exchange-rate")
public class OpenExchangeRateProvider implements ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenExchangeRateProvider.class); // 日志

    /** 缓存有效期（毫秒）：6 小时 */
    private static final long CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L;

    private final ExchangeRateApi api = InvokerFactory.getInvoker("http").create(ExchangeRateApi.class); // api

    /** 缓存的 USD 基准汇率表 */
    private volatile Map<String, Double> cachedRates = Collections.emptyMap();

    /** 缓存时间戳 */
    private volatile long cachedAt;

    @Override
    public String name() {
        return "open-exchange-rate";
    }

    /**
    * 获取指定币种汇率（1 单位 从 = ? 单位 转为）。
    *
    * <p>基于 USD 基准汇率换算：from -&gt; to = usdRate(to) / usdRate(from)。</p>
    *
    * @param from 源币种（ISO 4217，如 USD、CNY）
    * @param to   目标币种（ISO 4217，如 USD、CNY）
    * @return 汇率；数据源不可达或币种不存在时返回 空
    */
    @Override
    public BigDecimal getRate(String from, String to) {
        if (from == null || to == null) {
            return null;
        }
        Map<String, Double> rates = getRates("USD");
        Double source = rates.get(from.trim().toUpperCase());
        Double target = rates.get(to.trim().toUpperCase());
        if (source == null || target == null || source == 0.0d) {
            return null;
        }
        return BigDecimal.valueOf(target / source)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    /**
    * 获取以 USD 为基准的全量汇率表（带 6 小时内存缓存，惰性刷新）。
    *
    * @param base 基准币种，当前统一以 USD 为基准缓存（任意币对经 获取rate 换算）
    * @return 币种 -> 汇率（1 单位 USD 可兑换数量）；失败时返回上次缓存或空表
    */
    @Override
    public Map<String, Double> getRates(String base) {
        if (!cachedRates.isEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MILLIS) {
            return cachedRates;
        }
        try {
            ExchangeRateResponse response = api.latestUsd();
            if (response != null && "success".equals(response.getResult()) && response.getRates() != null) {
                cachedRates = response.getRates();
                cachedAt = System.currentTimeMillis();
                return cachedRates;
            }
        } catch (Exception e) {
            log.warn("[open-exchange-rate] 抓取失败: {}", e.getMessage());
        }
        return cachedRates;
    }
}
