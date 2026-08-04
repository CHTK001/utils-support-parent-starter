package com.chua.common.support.converter.definition;

import java.util.Optional;
import org.jspecify.annotations.NullUnmarked;


/**
 * Optional 类型转换器。
 * <p>将任意值包装为 {@link Optional}，通过 Optional.ofNullable(value) 实现。</p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/11/26
 */
@NullUnmarked
@SuppressWarnings("ALL")
public class OptionalConverter implements TypeConverter<Optional> {

    @Override
    public Class<Optional> getType() {
        return Optional.class;
    }

    /**
     * 将给定值转换为 Optional。
     *
     * @param value 源值
     * @return Optional 包装的值
     */
    @Override
    public Optional convert(Object value) {
        return Optional.ofNullable(value);
    }
}
