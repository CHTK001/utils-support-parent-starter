package com.chua.common.support.converter.definition;

import java.time.ZoneId;
import java.util.TimeZone;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* TimeZone 类型转换器。
* <p>将各种类型的值转换为 {@link TimeZone}，支持以下输入类型：</p>
* <ul>
*   <li>{@link ZoneId} — 通过 TimeZone.getTimeZone(ZoneId) 转换</li>
*   <li>{@link String} — 通过 TimeZone.getTimeZone(String) 解析时区 ID</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2020/12/31
 */
public class TimeZoneTypeConverter implements TypeConverter<TimeZone> {

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return TimeZone.class
     */
    @Override
    public Class<TimeZone> getType() {
        return TimeZone.class;
    }

    /**
    * 将给定值转换为 TimeZone。
    *
    * @param value 源值
    * @return TimeZone 值，如果无法转换则返回 null
     */
    @Override
    public TimeZone convert(Object value) {
        if (value instanceof ZoneId) {
            return TimeZone.getTimeZone((ZoneId) value);
        }

        if (value instanceof String) {
            return TimeZone.getTimeZone((String) value);
        }
        return null;
    }
}
