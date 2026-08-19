package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.UsageProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * Gemini CLI 用量提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("gemini-cli")
public class GeminiCliUsageProvider implements UsageProvider {
    @Override
    /** 获取Usage */
    public List<AiUsage> getUsage() { return List.of(); }
}