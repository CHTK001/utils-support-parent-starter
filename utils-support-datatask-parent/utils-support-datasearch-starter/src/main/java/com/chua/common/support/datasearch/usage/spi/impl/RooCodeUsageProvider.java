package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.UsageProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * RooCode 用量提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("roocode")
public class RooCodeUsageProvider implements UsageProvider {
    @Override
    public List<AiUsage> getUsage() { return List.of(); }
}