package com.chua.common.support.converter.definition;


import com.chua.common.support.utils.DateUtils;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Date;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Date 类型转换器。
* <p>将各种类型的值转换为 {@link Date}，支持以下输入类型：</p>
* <ul>
*   <li>{@link java.time.LocalDate} / {@link java.time.LocalDateTime} / {@link java.util.Calendar} / {@link Long} — 通过 DateUtils 转换</li>
*   <li>{@link java.sql.Date} — 直接返回（子类）</li>
*   <li>{@link String} — 通过 DateUtils.parseDate 解析日期字符串</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2020/11/26
 */
public class DateTypeConverter implements TypeConverter<Date> {

    /**
    * 将给定值转换为 Date。
    *
    * @param value 源值
    * @return Date 值，如果无法转换则返回 null
    */
    @Override
    public Date convert(Object value) {
        Date date = convertIfNecessary(value);
        if (null != date) {
            return date;
        }

        if(value instanceof LocalDate) {
            return DateUtils.parseDate((LocalDate) value);
        }

        if(value instanceof LocalDateTime) {
            return DateUtils.parseDate((LocalDateTime) value);
        }

        if(value instanceof Calendar) {
            return DateUtils.parseDate((Calendar) value);
        }

        if(value instanceof Long) {
            return DateUtils.parseDate((Long) value);
        }

        if(value instanceof java.sql.Date) {
            return (Date) value;
        }
        //                     
        String string = value.toString();
        try {
            return DateUtils.parseDate(string);
        } catch (ParseException ignored) {
        }
        return null;
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Date.class
    */
    @Override
    public Class<Date> getType() {
        return Date.class;
    }
}
