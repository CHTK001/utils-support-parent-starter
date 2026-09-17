package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.DateUtils;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Date;


/**
* LocalDate 类型转换器。
* <p>将各种类型的值转换为 {@link LocalDate}，支持以下输入类型：</p>
* <ul>
*   <li>{@link java.time.LocalDateTime} / {@link Date} / {@link java.time.LocalTime} / {@link Long} / {@link java.util.Calendar} / {@link java.time.Instant} — 通过 DateUtils 转换</li>
*   <li>{@link String} — 通过 DateUtils.toLocalDate 解析日期字符串</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2021/1/26
 */
public class LocalDateTypeConverter implements TypeConverter<LocalDate> {
    /**
    * 将给定值转换为 LocalDate。
    *
    * @param value 源值
    * @return LocalDate 值，如果无法转换则返回 null
    */
    @Override
    public LocalDate convert(Object value) {
        if (null == value) {
            return null;
        }

        if (isAssignableFrom(value, LocalDateTime.class)) {
            return (LocalDate) value;
        }

        if (isAssignableFrom(value, Date.class)) {
            return DateUtils.toLocalDate((Date) value);
        }

        if (isAssignableFrom(value, LocalTime.class)) {
            return DateUtils.toLocalDate((LocalTime) value);
        }

        if (isAssignableFrom(value, Long.class)) {
            return DateUtils.toLocalDate((Long) value);
        }

        if (isAssignableFrom(value, Calendar.class)) {
            return DateUtils.toLocalDate((Calendar) value);
        }

        if (isAssignableFrom(value, Instant.class)) {
            return DateUtils.toLocalDate((Instant) value);
        }

        if (isAssignableFrom(value, String.class)) {
            try {
                return DateUtils.toLocalDate((String) value);
            } catch (ParseException ignore) {
            }
        }

        return convertIfNecessary(value);
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return LocalDate.class
    */
    @Override
    public Class<LocalDate> getType() {
        return LocalDate.class;
    }
}
