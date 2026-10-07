package com.chua.common.support.lang.date;

import com.chua.common.support.spi.annotations.Spi;

import java.time.LocalDateTime;

/**
 * 标准 ISO 日期时间解析器。
 *
 * <p>common-starter 内置的默认 {@link DateTimeParser} 实现，
 * 直接委托 {@link LocalDateTime#parse(CharSequence)}，
 * 保证无任何可选模块时 {@link DateTime#parse(String)} 行为不变。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("standard")
public class StandardDateTimeParser implements DateTimeParser {

    @Override
    public LocalDateTime parse(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(timeStr);
        } catch (Exception ignored) {
            return null;
        }
    }
}
