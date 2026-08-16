package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.UsageProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.util.List;

/**
 * Cursor BYOK 用量提供者
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("cursor-byok")
public class CursorByokUsageProvider implements UsageProvider {
    @Override
    public List<AiUsage> getUsage() { return List.of(); }
}
