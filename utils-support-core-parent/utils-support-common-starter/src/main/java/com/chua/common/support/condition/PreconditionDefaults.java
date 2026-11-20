package com.chua.common.support.condition;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JDK 内置类型默认值表，供 {@link Precondition.Value} 与 {@link Precondition.Try}
 * 的 {@code orElseGetDefault} 兜底取值使用。
 *
 * @author CH
 * @since 4.0.0.53
 */
final class PreconditionDefaults {

    /**
     * JDK 自带类型的默认值表：数值 0、Boolean.FALSE、String ""、Optional.empty、
     * 空集合等共享静态实例；仅覆盖 JDK 内置类型，未登记的类型取值为 null，
     * 不做任何反射实例化。
     */
    private static final Map<Class<?>, Object> JDK_DEFAULTS = Map.ofEntries(
            Map.entry(Integer.class, 0),
            Map.entry(int.class, 0),
            Map.entry(Long.class, 0L),
            Map.entry(long.class, 0L),
            Map.entry(Double.class, 0.0D),
            Map.entry(double.class, 0.0D),
            Map.entry(Float.class, 0.0F),
            Map.entry(float.class, 0.0F),
            Map.entry(Short.class, (short) 0),
            Map.entry(short.class, (short) 0),
            Map.entry(Byte.class, (byte) 0),
            Map.entry(byte.class, (byte) 0),
            Map.entry(Character.class, '\0'),
            Map.entry(char.class, '\0'),
            Map.entry(Boolean.class, Boolean.FALSE),
            Map.entry(boolean.class, Boolean.FALSE),
            Map.entry(String.class, ""),
            Map.entry(Optional.class, Optional.empty()),
            Map.entry(List.class, Collections.emptyList()),
            Map.entry(Set.class, Collections.emptySet()),
            Map.entry(Map.class, Collections.emptyMap()));

    private PreconditionDefaults() {
    }

    /**
     * 按返回类型取 JDK 内置默认值；未登记类型（含业务类型）一律返回 null。
     *
     * @param type 返回类型
     * @param <R>  值类型
     * @return JDK 默认值或 null
     */
    @SuppressWarnings("unchecked")
    static <R> R defaultValue(Class<?> type) {
        if (type == null) {
            return null;
        }
        return (R) JDK_DEFAULTS.get(type);
    }
}
