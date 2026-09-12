package com.chua.common.support.converter;

import java.util.Map;

/**
* 字段转换器接口 — 在字段读写时进行自定义转换。
*
* <p>配合 {@link com.chua.common.support.annotation.FieldProperty @FieldProperty} 注解使用，
* 通过 {@code reader} / {@code writer} 属性指定。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* public class MoneyConverter implements FieldConverter {
*     public Object convert(Object source, FieldMappingContext context) {
*         if (source instanceof BigDecimal bd) {
*             return "¥" + bd.setScale(2, RoundingMode.HALF_UP);
*         }
*         return source;
*     }
* }
*
* // 在 POJO 中使用
* @FieldProperty(writer = MoneyConverter.class)
* private BigDecimal salary;
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public interface FieldConverter {

    /**
    * 执行字段转换。
    *
    * @param source  源值（字段原始值或字符串值）
    * @param context 转换上下文（包含格式化配置、上下文数据等）
    * @return 转换后的值
     */
    Object convert(Object source, FieldMappingContext context);
}
