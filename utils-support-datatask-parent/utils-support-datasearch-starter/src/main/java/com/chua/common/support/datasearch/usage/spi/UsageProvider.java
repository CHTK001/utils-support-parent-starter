package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;

import java.util.List;

/**
 * AI 用量数据提供者接口
 *
 * @author CH
 * @since 1.0.0
 */
public interface UsageProvider {

    /**
     * 获取全量 usage 数据
     *
     * @return AI 用量数据列表
     */
    List<AiUsage> getUsage();
}
