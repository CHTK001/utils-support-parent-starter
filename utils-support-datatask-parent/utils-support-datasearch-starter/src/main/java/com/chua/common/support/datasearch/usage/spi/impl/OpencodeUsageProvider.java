package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.UsageProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * OpenCode 用量提供者 — 聚合 ChatClient 调用时的用量存储
 *
 * <p>实际用量数据由 ChatClient → AiUsageRecord → Engine 持久化，
 * 外部解析请使用 {@link com.chua.common.support.datasearch.usage.spi.UsageParser}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("opencode")
public class OpencodeUsageProvider implements UsageProvider {

    @Override
    public List<AiUsage> getUsage() {
        return List.of();
    }
}
