package com.chua.common.support.converter.definition;


import com.chua.common.support.utils.ClassUtils;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_DOT;


/**
* Class 类型转换器。
* <p>将各种类型的值转换为 {@link Class}，支持以下输入：</p>
* <ul>
*   <li>字符串（包含包名分隔符）— 通过 ClassUtils.forName() 加载</li>
*   <li>其他类型 — 直接返回 getClass()</li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2020/11/26
 */
@SuppressWarnings("ALL")
public class ClassTypeConverter implements TypeConverter<Class> {

    /**
    * 将给定值转换为 Class。
    *
    * @param value 源值
    * @return Class 值，如果为 null 则返回 Void.class
     */
    @Override
    public Class convert(Object value) {
        if (null == value) {
            return Void.class;
        }

        if (value instanceof String && ((String) value).contains(SYMBOL_DOT)) {
            return ClassUtils.forName(value.toString());
        }

        return value.getClass();
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Class.class
     */
    @Override
    public Class<Class> getType() {
        return Class.class;
    }
}
