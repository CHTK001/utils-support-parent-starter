package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Date 转 LocalDateTime 转换器
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
     * @param zoneId 时区，例如 ZoneId.of("Asia/Shanghai")
     */
    public DateToLocalDateTimeConverter(ZoneId zoneId) {
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    /**
     * 将 Date 转换为 LocalDateTime
     *
     * @param source 源 Date 对象
     * @return LocalDateTime 对象，如果 source 为 null 则返回 null
     */
    @Override
    public LocalDateTime convert(Date source) {
        if (source == null) {
            return null;
        }
        return Instant.ofEpochMilli(source.getTime()).atZone(zoneId).toLocalDateTime();
    }
}
