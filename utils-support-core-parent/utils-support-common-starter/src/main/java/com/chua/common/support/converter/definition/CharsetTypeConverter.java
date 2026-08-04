package com.chua.common.support.converter.definition;

import java.nio.charset.Charset;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NullUnmarked;


/**
 * Charset 类型转换器。
 * <p>将字符串转换为 {@link Charset}，如 "UTF-8" → Charset.forName("UTF-8")。</p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/12/31
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class CharsetTypeConverter implements TypeConverter<Charset> {

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Charset.class
     */
    @Override
    public Class<Charset> getType() {
        return Charset.class;
    }

    /**
     * 将给定值转换为 Charset。
     * <p>通过 Charset.forName() 解析字符集名称字符串。</p>
     *
     * @param value 源值（字符串格式的字符集名称）
     * @return Charset 值，如果无法识别则返回 null
     */
    @Override
    public Charset convert(Object value) {
        if (value instanceof String) {
            try {
                return Charset.forName(value.toString());
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
