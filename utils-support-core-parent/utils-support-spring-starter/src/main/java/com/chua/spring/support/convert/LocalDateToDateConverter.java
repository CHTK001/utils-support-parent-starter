package com.chua.spring.support.convert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/**
* 本地日期 转 日期 转换器
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
    * @param zoneId 时区，例如 zoneid.的("Asia/Shanghai")
    */
    public LocalDateToDateConverter(ZoneId zoneId) {
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    /**
    * 将 本地日期 转换为 日期
    *
    * @param source 源 本地日期 对象
    * @return Date 对象，如果 源 为 空 则返回 空
    */
    @Override
    public Date convert(LocalDate source) {
        if (source == null) {
            return null;
        }
        return Date.from(source.atStartOfDay(zoneId).toInstant());
    }
}
