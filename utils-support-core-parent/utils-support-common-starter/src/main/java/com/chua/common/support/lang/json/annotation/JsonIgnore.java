package com.chua.common.support.lang.json.annotation;

import java.lang.annotation.*;

/**
* 统一 JSON 忽略字段注解（门户注解）。
*
* <p>标注在实体类的字段或 getter 方法上，序列化 / 反序列化时忽略该字段，
* 各 {@code JsonProvider} 实现（Jackson / Gson / Fory）统一适配识别，
* 业务代码无需再依赖具体 JSON 库的忽略注解（如 {@code @JsonIgnore}、{@code @Expose}）。</p>
*
* <p>使用示例：</p>
* <pre>{@code
* public class User {
*     private String name;
*
*     {@code @JsonIgnore}
*     private String password;
* }
*
* Json.toJson(user);  // {"name":"chua"}，password 不参与序列化
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface JsonIgnore {
}
