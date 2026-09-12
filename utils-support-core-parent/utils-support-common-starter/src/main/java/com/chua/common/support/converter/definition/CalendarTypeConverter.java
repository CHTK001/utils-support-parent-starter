package com.chua.common.support.converter.definition;

import java.time.*;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Calendar 类型转换器。
* <p>将各种类型的值转换为 {@link java.util.Calendar}，支持以下输入类型：</p>
* <ul>
*   <li>{@link Date} — 通过 GregorianCalendar 设置时间</li>
*   <li>{@link java.time.LocalDateTime} / {@link java.time.LocalDate} / {@link java.time.LocalTime} — 先转为 Date 再构造 Calendar</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2020/11/26
 */
public class CalendarTypeConverter implements TypeConverter<Calendar> {

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Calendar.class
     */
    @Override
    public Class<Calendar> getType() {
        return Calendar.class;
    }

    /**
    * 将给定值转换为 Calendar。
    *
    * @param value 源值
    * @return Calendar 值，如果无法转换则返回 null
     */
    @Override
    public Calendar convert(Object value) {
        if (value instanceof Date) {
            Calendar calendar = new GregorianCalendar();
            calendar.setTime((Date) value);
            return calendar;
        }

        if (value instanceof LocalDateTime) {
            Calendar calendar = new GregorianCalendar();
            calendar.setTime(Date.from(((LocalDateTime) value).atZone(ZoneOffset.systemDefault()).toInstant()));
            return calendar;
        }

        if (value instanceof LocalDate) {
            Calendar calendar = new GregorianCalendar();
            calendar.setTime(Date.from(((LocalDate) value).atStartOfDay(ZoneOffset.systemDefault()).toInstant()));
            return calendar;
        }

        if (value instanceof LocalTime) {
            Calendar calendar = new GregorianCalendar();
            calendar.setTime(Date.from(Instant.ofEpochSecond(((LocalTime) value).getSecond(), ((LocalTime) value).getNano())));
            return calendar;
        }

        return null;
    }
}
