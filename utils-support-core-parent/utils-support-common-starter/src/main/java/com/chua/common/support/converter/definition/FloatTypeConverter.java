package com.chua.common.support.converter.definition;

import java.math.BigDecimal;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Float 类型转换器。
* <p>将各种类型的值转换为 {@link Float}，通过 {@link #transToBigDecimal(Object)} 转为 BigDecimal 后取 floatValue。</p>
*
* @author CH
* @version 1.0.0
* @since 2020/10/30
 */
public class FloatTypeConverter implements TypeConverter<Float> {

    /**
    * 将给定值转换为 Float。
    * <p>通过 {@link #transToBigDecimal(Object)} 转为 BigDecimal 后取 floatValue，
    * 无法转换时兜底调用 {@link #convertIfNecessary(Object)}。</p>
    *
    * @param value 源值
    * @return Float 值，如果无法转换则返回 null
    */
    @Override
    public Float convert(Object value) {
        if (null == value) {
            return null;
        }

        BigDecimal bigDecimal = transToBigDecimal(value);
        if (null != bigDecimal) {
            return bigDecimal.floatValue();
        }
        return convertIfNecessary(value);
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Float.class
    */
    @Override
    public Class<Float> getType() {
        return Float.class;
    }
}
