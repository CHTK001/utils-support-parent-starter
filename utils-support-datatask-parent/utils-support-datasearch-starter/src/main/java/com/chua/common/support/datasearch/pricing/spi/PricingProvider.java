package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;

import java.util.List;

/**
 * AI 模型定价数据提供者接口。
 *
 * <p>各厂商通过此接口提供模型定价信息，支持本地缓存和在线同步。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface PricingProvider {

    /**
     * 获取数据源名称。
     *
     * @return 厂商标识
     */
    String name();

    /**
     * 获取模型定价列表。
     *
     * <p>优先从本地缓存加载，本地无数据时返回内置定价。</p>
     *
     * @return 模型定价列表
     */
    List<ModelDefinition> getPricing();

    /**
     * 将内置定价数据同步到本地缓存。
     */
    default void syncToLocal() {
    }
}
