package com.chua.common.support.lang.json.annotation;

import java.lang.annotation.*;

/**
* 统一 JSON 日期格式注解（门户注解）。
*
* <p>标注在实体类的字段或 getter 方法上，指定日期 / 时间类型字段的序列化格式，
* 各 {@code JsonProvider} 实现（Jackson / Gson / Fory）统一适配识别。</p>
*
* <p>支持 {@code java.util.Date}、{@code java.time.LocalDateTime}、{@code java.time.LocalDate}、
* {@code java.time.LocalTime} 等常见日期类型，格式与 {@code SimpleDateFormat} / {@code DateTimeFormatter}
* 的 pattern 一致，如 {@code "yyyy-MM-dd HH:mm:ss"}。</p>
*
* <p>使用示例：</p>
* <pre>{@code
* public class Order {
*     {@code @JsonFormat("yyyy-MM-dd HH:mm:ss")}
*     private LocalDateTime createTime;
* }
*
* Json.toJson(order);  // {"createTime":"2026-08-15 12:30:00"}
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface JsonFormat {

    /**
    * 日期时间格式 pattern。
    *
    * @return 日期时间格式 pattern
    */
    String value();
}
