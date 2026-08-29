package com.chua.common.support.datasearch.exchange;

import com.chua.common.support.datasearch.exchange.spi.ExchangeRateProvider;
import com.chua.common.support.datasearch.exchange.spi.impl.OpenExchangeRateProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 汇率真实数据验证:SPI 发现 + HttpInvoker 实体查询 + USD/CNY 换算。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ExchangeRateVerifyTest {

    /**
     * 运行验证。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        // 1) SPI 发现
        ExchangeRateProvider provider = ServiceProvider.of(ExchangeRateProvider.class)
                .getExtension("open-exchange-rate");
        System.out.println("SPI 发现 open-exchange-rate: " + (provider != null));
        if (provider == null) {
            provider = new OpenExchangeRateProvider();
        }

        // 2) 真实汇率(HttpInvoker 实体查询链路)
        BigDecimal usd2cny = provider.getRate("USD", "CNY");
        BigDecimal cny2usd = provider.getRate("CNY", "USD");
        BigDecimal eur2cny = provider.getRate("EUR", "CNY");
        BigDecimal jpy2cny = provider.getRate("JPY", "CNY");
        System.out.println("USD->CNY: " + usd2cny);
        System.out.println("CNY->USD: " + cny2usd);
        System.out.println("EUR->CNY: " + eur2cny);
        System.out.println("JPY->CNY: " + jpy2cny);

        // 3) 一致性:USD->CNY 与 CNY->USD 应互为倒数
        boolean reciprocal = usd2cny != null && cny2usd != null
                && usd2cny.multiply(cny2usd).doubleValue() > 0.95
                && usd2cny.multiply(cny2usd).doubleValue() < 1.05;
        System.out.println("USD/CNY 互为倒数(≈1): " + reciprocal);

        // 4) 全量汇率表(缓存)
        Map<String, Double> rates = provider.getRates("USD");
        System.out.println("USD 基准币种数: " + rates.size() + ", CNY=" + rates.get("CNY"));
    }
}
