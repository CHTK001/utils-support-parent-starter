package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.DateUtils;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Date;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Instant 类型转换器。
* <p>将各种类型的值转换为 {@link java.time.Instant}，支持以下输入类型：</p>
* <ul>
*   <li>{@link Date} / {@link java.time.LocalDate} / {@link java.time.LocalTime} — 通过 DateUtils 转换</li>
*   <li>{@link String} — 先解析为 Date 再转为 Instant</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public class InstantTypeConverter implements TypeConverter<Instant>{
    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Instant.class
     */
    @Override
    public Class<Instant> getType() {
        return Instant.class;
    }

    /**
    * 将给定值转换为 Instant。
    *
    * @param value 源值
    * @return Instant 值，如果无法转换则返回 null
     */
    @Override
    public Instant convert(Object value) {
        if(value instanceof Date) {
            return DateUtils.toInstant((Date) value);
        }

        if(value instanceof LocalDate) {
            return DateUtils.toInstant((LocalDate) value);
        }

        if(value instanceof LocalTime) {
            return DateUtils.toInstant((LocalTime) value);
        }

        if(value instanceof String) {
            try {
                return DateUtils.toInstant(DateUtils.parseDate(value.toString()));
            } catch (ParseException ignored) {
            }
        }
        return null;
    }
}
