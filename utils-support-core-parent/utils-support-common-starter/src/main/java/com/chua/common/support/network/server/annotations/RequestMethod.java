/*
 *   Copyright 2023 CH
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.chua.common.support.network.server.annotations;

import com.chua.common.support.network.http.HttpMethod;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 通用请求方法注解，用于将请求映射到处理方法上。
 *
 * <p>可标注在类或方法级别：类级别为公共前缀，方法级别为具体路径。
 * 适用于 HTTP 服务端、IPC 服务端路由注册等场景。</p>
 *
 * <p>示例：</p>
 * <pre>{@code
 * @RequestMethod("/api")
 * public class UserApi {
 *     @RequestMethod(value = "/users/{id}", method = HttpMethod.GET)
 *     public User getUser(@PathVariable("id") String id) { ... }
 * }
 * }</pre>
 *
 * @author CH
 * @since 2024/12/12
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestMethod {

    /**
     * 请求路径（支持多路径）
     *
     * @return 路径数组
     */
    String[] value() default {};

    /**
     * HTTP 方法（支持多方法，空数组表示匹配所有方法）
     *
     * @return HTTP 方法数组
     */
    HttpMethod[] method() default {};

    /**
     * 描述信息
     *
     * @return 描述
     */
    String description() default "";
}