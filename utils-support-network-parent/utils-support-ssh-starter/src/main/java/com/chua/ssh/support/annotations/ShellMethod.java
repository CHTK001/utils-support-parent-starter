package com.chua.ssh.support.annotations;

import java.lang.annotation.*;

/**
* Shell 方法注解，用于声明式 SSH Shell 命令。
*
* <p>类似 {@code @IpcMethod} 和 {@code @RequestMethod}，
* 标记可被 SSH 客户端通过 Shell 协议调用的 Java 方法。
* 与 {@code @RequestMethod} 结构一致，专为 SSH 协议设计。</p>
*
* <p>支持两种使用方式：</p>
* <ul>
*   <li>类级别：为类中所有方法定义命令路径前缀</li>
*   <li>方法级别：定义具体命令名称和描述</li>
* </ul>
*
* <p>示例：</p>
* <pre>{@code
* @ShellMethod("/api")
* public class FileHandler {
*     @ShellMethod(value = "ls", description = "列出目录内容")
*     public String ls(String[] args) { ... }
*
*     @ShellMethod("pwd", description = "显示当前工作目录")
*     public String pwd(String[] args) { ... }
* }
* }</pre> pwd(字符串[] 参数) { ... }
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see com.chua.ssh.support.server.SshServer
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ShellMethod {

    /**
    * 命令名称或路径前缀。
    *
    * <p>方法级别时为完整的命令名称（如 {@code "ls"}），
    * 类级别时为路径前缀（如 {@code "/file"}）。</p>
    *
    * @return 命令名称或路径前缀
    */
    String value();

    /**
    * 命令描述，用于 help 和文档。
    *
    * @return 命令描述
    */
    String description() default "";

    /**
    * 命令的 HTTP 方法等效值（可选）。
    *
    * @return 方法名，默认为空
    */
    String method() default "";

    /**
    * 输出格式，指定 {@link com.chua.common.support.lang.view.ViewParser} SPI 名称。
    * <p>可选值：table、plain、list、md、kv、card、tree、barchart、json，默认空表示自动检测。</p>
    *
    * @return 视图解析器名称
    */
    String produce() default "";
}
