package com.chua.common.support.converter.definition;

import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Boolean 类型转换器。
 * <p>将各种类型的值转换为 {@link Boolean}，支持以下特性：</p>
 * <ul>
 *   <li>字符串匹配：不区分大小写识别 true/t/yes/y/on/1（真）和 false/f/no/n/off/0（假）</li>
 *   <li>数值类型：非零值为 true，零值为 false</li>
 *   <li>字符类型：通过字符串匹配规则判断</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 */
@Slf4j
public class BooleanTypeConverter implements TypeConverter<Boolean> {

    /** 识别为 true 的字符串集合 */
    /** True_words */
    private static final Set<String> TRUE_WORDS = new HashSet<>();
    /** 识别为 false 的字符串集合 */
    /** False_words */
    private static final Set<String> FALSE_WORDS = new HashSet<>();

    static {
        TRUE_WORDS.add("true");
        TRUE_WORDS.add("t");
        TRUE_WORDS.add("yes");
        TRUE_WORDS.add("y");
        TRUE_WORDS.add("on");
        TRUE_WORDS.add("1");

        FALSE_WORDS.add("false");
        FALSE_WORDS.add("f");
        FALSE_WORDS.add("no");
        FALSE_WORDS.add("n");
        FALSE_WORDS.add("off");
        FALSE_WORDS.add("0");
    }

    /**
     * 将给定值转换为 Boolean。
     *
     * @param value 源值
     * @return Boolean 值，如果无法转换则返回 null（null 输入返回 false）
     */
    @Override
    public Boolean convert(Object value) {
        if (null == value) {
            return false;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0D;
        }

        if (value instanceof Character) {
            return parse(String.valueOf((Character) value));
        }

        if (value instanceof String) {
            String s = value.toString();
            return parse(s);
        }
        return null;
    }

    /**
     * 解析字符串为 Boolean 值。
     *
     * @param s 字符串
     * @return Boolean 值，如果无法识别则返回 null
     */
    private Boolean parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.isEmpty()) {
            return false;
        }
        String lower = v.toLowerCase();
        if (TRUE_WORDS.contains(lower) || TRUE_WORDS.contains(v)) {
            return true;
        }
        if (FALSE_WORDS.contains(lower) || FALSE_WORDS.contains(v)) {
            return false;
        }
        try {
            return Boolean.parseBoolean(lower);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Boolean parse failed. value='{}', error={}", s, e.getMessage());
            }
            return null;
        }
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return Boolean.class
     */
    @Override
    public Class<Boolean> getType() {
        return Boolean.class;
    }
}
