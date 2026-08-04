package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.CollectionUtils;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NullUnmarked;


/**
 * Integer 类型转换器。
 * <p>将各种类型的值转换为 {@link Integer}，支持数组/集合取首元素、BigDecimal 转换等。</p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2020/10/30
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class IntegerTypeConverter implements TypeConverter<Integer> {

    /**
     * 将给定值转换为 Integer。
     * <p>转换逻辑：</p>
     * <ol>
     *   <li>如果值是数组，取第一个元素</li>
     *   <li>如果值是集合，取第一个元素</li>
     *   <li>通过 {@link #transToBigDecimal(Object)} 转为 BigDecimal 后取 intValue</li>
     *   <li>兜底调用 {@link #convertIfNecessary(Object)}</li>
     * </ol>
     *
     * @param value 源值
     * @return Integer 值，如果无法转换则返回 null
     */
    @Override
    public Integer convert(Object value) {
        if (null == value) {
            return null;
        }

        if (value.getClass().isArray()) {
            value = Array.get(value, 0);
        }

        if (value instanceof Collection) {
            value = CollectionUtils.findFirst((Collection) value);
        }

        BigDecimal bigDecimal = transToBigDecimal(value);
        if (null != bigDecimal) {
            return bigDecimal.intValue();
        }
        return convertIfNecessary(value);
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Integer.class
     */
    @Override
    public Class<Integer> getType() {
        return Integer.class;
    }
}
