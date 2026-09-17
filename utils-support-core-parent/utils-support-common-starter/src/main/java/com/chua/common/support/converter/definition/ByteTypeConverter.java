package com.chua.common.support.converter.definition;

import java.math.BigDecimal;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Byte 类型转换器。
* <p>将各种类型的值转换为 {@link Byte}，通过 {@link #transToBigDecimal(Object)} 转为 BigDecimal 后取 byteValue。</p>
*
* @author CH
* @version 1.0.0
* @since 2020/10/30
 */
public class ByteTypeConverter implements TypeConverter<Byte> {

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Byte.class
    */
    @Override
    public Class<Byte> getType() {
        return Byte.class;
    }

    /**
    * 将给定值转换为 Byte。
    * <p>通过 {@link #transToBigDecimal(Object)} 转为 BigDecimal 后取 byteValue，
    * 无法转换时兜底调用 {@link #convertIfNecessary(Object)}。</p>
    *
    * @param value 源值
    * @return Byte 值，如果无法转换则返回 null
    */
    @Override
    public Byte convert(Object value) {
        BigDecimal bigDecimal = transToBigDecimal(value);
        if (null != bigDecimal) {
            return bigDecimal.byteValue();
        }
        return convertIfNecessary(value);
    }
}
