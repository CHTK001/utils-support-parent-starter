package com.chua.common.support.datasearch.pricing.spi;

import com.chua.common.support.ai.chat.ModelDefinition;

import java.util.List;

/**
 * AI 模型指标提供者接口。
 *
 * <p>各数据源通过此接口提供模型的多维指标（价格、智能指数、输出速度、延迟、图标等），
 * 支持在线同步与本地缓存。命名上覆盖"价格"之外的更多评测维度。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ModelMetricsProvider {

    /**
     * 获取数据源名称。
     *
     * @return 数据源标识
     */
    String name();

    /**
     * 获取模型指标列表。
     *
     * <p>优先读取本地缓存，无缓存时返回空列表；
     * 通过 {@link #syncFromOnline()} 在线同步填充。</p>
     *
     * @return 模型指标列表
     */
    List<ModelDefinition> getMetrics();

    /**
     * 从线上同步指标数据并持久化到本地。
     */
    void syncFromOnline();
}
