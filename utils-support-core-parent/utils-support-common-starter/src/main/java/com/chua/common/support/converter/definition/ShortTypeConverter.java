package com.chua.common.support.converter.definition;

import java.math.BigDecimal;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Short 类型转换器。
* <p>将各种类型的值转换为 {@link Short}，通过 {@link #transToBigDecimal(Object)} 转为 BigDecimal 后取 shortValue。</p>
*
* @author CH
* @version 1.0.0
* @since 2020/10/30
 */
public class ShortTypeConverter implements TypeConverter<Short> {

    /**
    * 将给定值转换为 Short。
    * <p>通过 {@link #transToBigDecimal(Object)} 转为 BigDecimal 后取 shortValue。</p>
    *
    * @param value 源值
    * @return Short 值，如果无法转换则返回 null
    */
    @Override
    public Short convert(Object value) {
        if (null == value) {
            return null;
        }

        BigDecimal bigDecimal = transToBigDecimal(value);
        if (null != bigDecimal) {
            return bigDecimal.shortValue();
        }
        return null;
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Short.class
    */
    @Override
    public Class<Short> getType() {
        return Short.class;
    }
}
