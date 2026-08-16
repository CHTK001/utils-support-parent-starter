package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * LocalDateTime 转 Date 转换器
 * 将 {@link LocalDateTime} 转换为 {@link Date}
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LocalDateTimeToDateConverter implements Converter<LocalDateTime, Date> {

    /**
     * 时区，默认使用系统默认时区
     */
    private final ZoneId zoneId;

    /**
     * 使用默认时区构造转换器
     */
    public LocalDateTimeToDateConverter() {
        this(ZoneId.systemDefault());
    }

    /**
     * 使用指定时区构造转换器
     *
     * @param zoneId 时区，例如 ZoneId.of("Asia/Shanghai")
     */
    public LocalDateTimeToDateConverter(ZoneId zoneId) {
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    /**
     * 将 LocalDateTime 转换为 Date
     *
     * @param source 源 LocalDateTime 对象
     * @return Date 对象，如果 source 为 null 则返回 null
     */
    @Override
    public Date convert(LocalDateTime source) {
        if (source == null) {
            return null;
        }
        return Date.from(source.atZone(zoneId).toInstant());
    }
}
