package com.chua.common.support.converter.definition;

import java.math.BigDecimal;
import java.math.BigInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* BigInteger 类型转换器。
* <p>将各种类型的值转换为 {@link BigInteger}，通过 {@link #transToBigDecimal(Object)} 转为 BigDecimal 后取 longValue 构造 BigInteger。</p>
*
* @author CH
* @since 4.0.0.42
* @version 1.0.0
 */
public class BigIntegerTypeConverter implements TypeConverter<BigInteger> {

    /**
    * 将给定值转换为 BigInteger。
    * <p>通过 {@link #transToBigDecimal(Object)} 转为 BigDecimal 后取 longValue 构造 BigInteger，
    * 无法转换时兜底调用 {@link #convertIfNecessary(Object)}。</p>
    *
    * @param value 源值
    * @return BigInteger 值，如果无法转换则返回 null
     */
    @Override
    public BigInteger convert(Object value) {
        if (null == value) {
            return null;
        }

        BigDecimal bigDecimal = transToBigDecimal(value);
        if (null != bigDecimal) {
            return BigInteger.valueOf(bigDecimal.longValue());
        }
        return convertIfNecessary(value);
    }


    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return BigInteger.class
     */
    @Override
    public Class<BigInteger> getType() {
        return BigInteger.class;
    }
}
