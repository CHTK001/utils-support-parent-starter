package com.chua.common.support.lang.json.annotation;

import java.lang.annotation.*;

/**
 * 统一 JSON 字段名注解（门户注解）。
 *
 * <p>标注在实体类的字段或 getter 方法上，指定序列化 / 反序列化时使用的 JSON 字段名，
 * 各 {@code JsonProvider} 实现（Jackson / Gson / Fory）统一适配识别，
 * 业务代码无需再依赖具体 JSON 库的字段注解（如 {@code @JsonProperty}、{@code @SerializedName}）。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * public class User {
 *     {@code @JsonName("user_name")}
 *     private String userName;
 *
 *     // getter / setter ...
 * }
 *
 * Json.toJson(user);          // {"user_name":"chua"}
 * Json.fromJson(json, User.class);  // userName = "chua"
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface JsonName {

    /**
     * JSON 字段名。
     *
     * @return JSON 字段名
     */
    String value();
}
