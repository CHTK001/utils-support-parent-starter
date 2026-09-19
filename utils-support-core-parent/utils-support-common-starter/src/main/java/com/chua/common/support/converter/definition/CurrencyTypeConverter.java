package com.chua.common.support.converter.definition;

import java.util.Currency;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Currency 类型转换器。
 * <p>将 {@link Locale} 转换为 {@link Currency}，通过 Currency.getInstance(Locale) 获取。</p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/12/31
 */
public class CurrencyTypeConverter implements TypeConverter<Currency> {

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Currency.class
     */
    @Override
    public Class<Currency> getType() {
        return Currency.class;
    }

    /**
     * 将给定值转换为 Currency。
     *
     * @param value 源值（Locale 对象）
     * @return Currency 值，如果无法转换则返回 null
     */
    @Override
    public Currency convert(Object value) {
        if (value instanceof Locale) {
            return Currency.getInstance((Locale) value);
        }
        return null;
    }
}
