package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.DateUtils;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Date;
import org.jspecify.annotations.NullUnmarked;


/**
 * LocalTime 类型转换器。
 * <p>将各种类型的值转换为 {@link LocalTime}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link Date} / {@link java.time.LocalDate} / {@link Long} / {@link java.util.Calendar} / {@link java.time.Instant} — 通过 DateUtils 转换</li>
 *   <li>{@link String} — 通过 DateUtils.toLocalTime 解析时间字符串</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2021/1/26
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class LocalTimeTypeConverter implements TypeConverter<LocalTime> {


    /**
     * 将给定值转换为 LocalTime。
     *
     * @param value 源值
     * @return LocalTime 值，如果无法转换则返回 null
     */
    @Override
    public LocalTime convert(Object value) {
        if (null == value) {
            return null;
        }

        if (isAssignableFrom(value, LocalTime.class)) {
            return (LocalTime) value;
        }

        if (isAssignableFrom(value, Date.class)) {
            return DateUtils.toLocalTime((Date) value);
        }

        if (isAssignableFrom(value, LocalDate.class)) {
            return DateUtils.toLocalTime((LocalDate) value);
        }

        if (isAssignableFrom(value, Long.class)) {
            return DateUtils.toLocalTime((Long) value);
        }

        if (isAssignableFrom(value, Calendar.class)) {
            return DateUtils.toLocalTime((Calendar) value);
        }

        if (isAssignableFrom(value, Instant.class)) {
            return DateUtils.toLocalTime((Instant) value);
        }

        if (isAssignableFrom(value, String.class)) {
            try {
                return DateUtils.toLocalTime((String) value);
            } catch (ParseException ignore) {
            }
        }

        return convertIfNecessary(value);
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return LocalTime.class
     */
    @Override
    public Class<LocalTime> getType() {
        return LocalTime.class;
    }
}
