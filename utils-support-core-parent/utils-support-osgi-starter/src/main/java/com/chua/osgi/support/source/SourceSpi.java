package com.chua.osgi.support.source;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个源码编译产物为 SPI 扩展点实现。
 *
 * <p>用于 {@code plugin.osgi.source.directory} 下 {@code .java} 文件里
 * 那些「本身不实现业务接口、只作为若干 SPI 实现的宿主」的类。编译器扫描到
 * 带本注解的类后，会把其中<b>所有 public 字段</b>按
 * 「字段静态类型 = SPI 类型、字段名 = 扩展名」翻译成注册动作。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * @SourceSpi
 * public class MyExtensions {
 *     // 扩展名 MY、JSON，实现类即 DataProcessor
 *     public final DataProcessor json = new MyJsonProcessor();
 * }
 * }</pre>
 *
 * <p><b>为什么按字段而不是让类直接 implements 接口</b>：一个宿主类里通常要
 * 暴露多个扩展点（web / app / wechat …），字段方式让它们集中在一处可读，
 * 且不需要为每个扩展点各写一个文件。</p>
 *
 * <p><b>字段要求</b>：必须 {@code public}（反射读取）、非 {@code static}（宿主实例
 * 由编译器构造一次并复用，避免每次注册新建）、非 {@code final} 的判空由编译器承担
 * ——为 {@code null} 的字段会被跳过并告警，不阻断整批注册。</p>
 *
 * <p>实现类本身须有 {@code public 无参构造器}，否则无法实例化。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SourceSpi {
}
