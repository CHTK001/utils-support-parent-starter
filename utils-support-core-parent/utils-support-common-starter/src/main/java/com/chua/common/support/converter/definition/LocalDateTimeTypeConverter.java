package com.chua.common.support.converter.definition;


import com.chua.common.support.utils.DateUtils;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Date;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* LocalDateTime 类型转换器。
* <p>将各种类型的值转换为 {@link LocalDateTime}，支持以下输入类型：</p>
* <ul>
*   <li>{@link Date} / {@link java.time.LocalDate} / {@link java.time.LocalTime} / {@link Long} / {@link java.util.Calendar} / {@link java.time.Instant} — 通过 DateUtils 转换</li>
*   <li>{@link String} — 通过 DateUtils.parseLocalDateTimeSafe 解析日期时间字符串</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2021/1/26
 */
public class LocalDateTimeTypeConverter implements TypeConverter<LocalDateTime> {

    /**
    * 将给定值转换为 LocalDateTime。
    *
    * @param value 源值
    * @return LocalDateTime 值，如果无法转换则返回 null
     */
    @Override
    public LocalDateTime convert(Object value) {
        if (null == value) {
            return null;
        }

        if (isAssignableFrom(value, LocalDateTime.class)) {
            return (LocalDateTime) value;
        }

        if (isAssignableFrom(value, Date.class)) {
            return DateUtils.toLocalDateTime((Date) value);
        }

        if (isAssignableFrom(value, LocalDate.class)) {
            return DateUtils.toLocalDateTime((LocalDate) value);
        }

        if (isAssignableFrom(value, LocalTime.class)) {
            return DateUtils.toLocalDateTime((LocalTime) value);
        }

        if (isAssignableFrom(value, Long.class)) {
            return DateUtils.toLocalDateTime((Long) value);
        }

        if (isAssignableFrom(value, Calendar.class)) {
            return DateUtils.toLocalDateTime((Calendar) value);
        }

        if (isAssignableFrom(value, Instant.class)) {
            return DateUtils.toLocalDateTime((Instant) value);
        }

        if (isAssignableFrom(value, String.class)) {
            return DateUtils.parseLocalDateTimeSafe((String) value);
        }

        return convertIfNecessary(value);
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return LocalDateTime.class
     */
    @Override
    public Class<LocalDateTime> getType() {
        return LocalDateTime.class;
    }
}
