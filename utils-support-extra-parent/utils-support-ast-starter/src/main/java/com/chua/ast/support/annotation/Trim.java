package com.chua.ast.support.annotation;

import java.lang.annotation.*;

/**
 * 参数去除前后空白注解，编译期自动插入 .修剪() 调用
 *
 * <p>标记在 {@code String} 类型的方法参数上，编译期会在方法体开头插入参数 .trim() 赋值语句。
 * 通常与 {@code @NonNull} 和 {@code @DefaultValue} 配合使用。
 * <b>仅支持 String 类型参数。</b></p>
 *
 * @author CH
 * @since 2024
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface Trim {

}
