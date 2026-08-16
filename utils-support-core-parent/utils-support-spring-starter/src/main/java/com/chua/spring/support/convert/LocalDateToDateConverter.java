package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/**
 * LocalDate 转 Date 转换器
 * 将 {@link LocalDate} 转换为 {@link Date}
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LocalDateToDateConverter implements Converter<LocalDate, Date> {

    /**
     * 时区，默认使用系统默认时区
     */
    private final ZoneId zoneId;

    /**
     * 使用默认时区构造转换器
     */
    public LocalDateToDateConverter() {
        this(ZoneId.systemDefault());
    }

    /**
     * 使用指定时区构造转换器
     *
     * @param zoneId 时区，例如 ZoneId.of("Asia/Shanghai")
     */
    public LocalDateToDateConverter(ZoneId zoneId) {
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    /**
     * 将 LocalDate 转换为 Date
     *
     * @param source 源 LocalDate 对象
     * @return Date 对象，如果 source 为 null 则返回 null
     */
    @Override
    public Date convert(LocalDate source) {
        if (source == null) {
            return null;
        }
        return Date.from(source.atStartOfDay(zoneId).toInstant());
    }
}
