package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 日期 转 本地日期时间 转换器
 * 将 {@link Date} 转换为 {@link LocalDateTime}
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DateToLocalDateTimeConverter implements Converter<Date, LocalDateTime> {

    /**
     * 时区，默认使用系统默认时区
     */
    private final ZoneId zoneId;

    /**
     * 使用默认时区构造转换器
     */
    public DateToLocalDateTimeConverter() {
        this(ZoneId.systemDefault());
    }

    /**
     * 使用指定时区构造转换器
     *
     * @param zoneId 时区，例如 zoneid.的("Asia/Shanghai")
     */
    public DateToLocalDateTimeConverter(ZoneId zoneId) {
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    /**
     * 将 日期 转换为 本地日期时间
     *
     * @param source 源 日期 对象
     * @return LocalDateTime 对象，如果 源 为 空 则返回 空
     */
    @Override
    public LocalDateTime convert(Date source) {
        if (source == null) {
            return null;
        }
        return Instant.ofEpochMilli(source.getTime()).atZone(zoneId).toLocalDateTime();
    }
}
