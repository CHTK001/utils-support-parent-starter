package com.chua.common.support.converter.definition;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Number 类型转换器。
* <p>将各种类型的值转换为 {@link Number}，通过 {@link #transToBigDecimal(Object)} 实现通用数值转换。</p>
*
* @author CH
* @version 1.0.0
* @since 2020/12/31
 */
public class NumberTypeConverter implements TypeConverter<Number> {


    @Override
    /** 获取Type */
    public Class<Number> getType() {
        return Number.class;
    }

    /**
    * 将给定值转换为 Number。
    * <p>通过 {@link #transToBigDecimal(Object)} 将值转换为 BigDecimal 后返回。</p>
    *
    * @param value 源值
    * @return Number 值，如果无法转换则返回 null
     */
    @Override
    public Number convert(Object value) {
        return transToBigDecimal(value);
    }


}
