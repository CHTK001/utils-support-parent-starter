package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;

import java.util.List;

/**
 * AI 模型定价数据提供者接口。
 *
 * <p>各厂商通过此接口提供模型定价信息，支持在线同步和本地文件缓存。</p>
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
     * <p>优先从本地文件缓存加载，本地无文件时返回空列表。</p>
     *
     * @return 模型定价列表
     */
    List<ModelDefinition> getPricing();

    /**
     * 从线上 API 同步定价数据并持久化到本地文件。
     */
    void syncFromOnline();
}
