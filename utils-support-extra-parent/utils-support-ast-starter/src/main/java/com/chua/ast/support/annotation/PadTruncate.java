package com.chua.ast.support.annotation;

import java.lang.annotation.*;

/**
* 填充和截断注解，编译期自动插入字符串填充和截断代码
*
* <p>标记在 {@code String} 类型的方法参数上，编译期会在方法体开头
* 插入字符串填充（pad）和截断（truncate）代码。</p>
*
* <p>使用示例：</p>
* <pre>{@code
* // 使用方式：public void process(@PadTruncate(start=2, end=10) String name) { ... }
* // 转换后：
* public void process(String name) {
*     if (name != null) {
*         if (name.length() < 2) {
*             name = String.format("%" + 2 + "s", name);  // 左填充空格
*         }
*         if (name.length() > 10) {
*             name = name.substring(0, 10);  // 截断到最大长度
*         }
*     }
*     ...原始方法体...
* }
*
* // 自定义填充字符：@PadTruncate(start=2, end=10, padChar='0')
* }</pre> * }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.PARAMETER)
public @interface PadTruncate {

    /**
    * 最小长度，字符串长度小于此值时进行填充
    *
    * @return 最小长度
     */
    int start() default 0;

    /**
    * 最大长度，字符串长度大于此值时进行截断
    *
    * @return 最大长度
     */
    int end() default Integer.MAX_VALUE;

    /**
    * 填充字符，默认为空格
    *
    * @return 填充字符
     */
    char padChar() default ' ';
}
