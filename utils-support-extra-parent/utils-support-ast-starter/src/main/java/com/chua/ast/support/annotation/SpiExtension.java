package com.chua.ast.support.annotation;

import java.lang.annotation.*;

/**
 * SPI 扩展自动注册注解，编译期自动生成 {@code META-INF/extensions/} 索引文件
 *
 * <p>标注在 SPI 实现类上，编译期由 {@code SpiExtensionAstProcessor} 扫描，
 * 自动生成 {@code META-INF/extensions/<接口全限定名>} 配置文件，免去手动维护 SPI 索引。
 * 生成的文件格式与运行时 {@code CustomServiceResolver} 的解析格式完全一致：
 * <ul>
 *     <li>{@code 实现类全限定名}</li>
 *     <li>{@code 别名=实现类全限定名}</li>
 * </ul>
 * </p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 显式指定 SPI 接口与别名
 * @SpiExtension(value = "com.chua.common.support.ai.embedding.EmbeddingClient", name = "minilm")
 * public class MiniLMEmbeddingClient implements EmbeddingClient { ... }
 *
 * // 省略接口：自动从实现类推导（递归收集类及其父类实现的所有非 JDK 接口）
 * // 省略别名：优先读取类上的 @Spi / @Extension 注解，否则取「类名去掉接口名」推导
 * @SpiExtension
 * public class BgeEmbeddingClient implements EmbeddingClient { ... }
 * }</pre>
 *
 * <p>与运行时注解的关系：若实现类上已标注 common-starter 的 {@code @Spi} 或 {@code @Extension}，
 * 其 value 会被自动用作别名写入索引文件；未标注时按「类名去掉接口名」推导
 * （如 {@code MiniLMEmbeddingClient} 实现 {@code EmbeddingClient} 推导为 {@code MiniLM}）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface SpiExtension {

    /**
     * SPI 接口全限定名数组
     *
     * <p>缺省时自动推导：递归收集实现类及其父类实现的所有非 JDK 接口，每个接口生成一份索引文件。</p>
     *
     * <p>使用示例：</p>
     * <pre>{@code
     * // 显式指定接口
     * @SpiExtension(value = "com.chua.common.support.ai.embedding.EmbeddingClient", name = "bge")
     *
     * // 自动推导实现接口
     * @SpiExtension
     * }</pre>
     *
     * @return SPI 接口全限定名数组
     */
    String[] value() default {};

    /**
     * 扩展名（别名）数组
     *
     * <p>缺省时按以下顺序推导：</p>
     * <ol>
     *     <li>实现类上的 {@code com.chua.common.support.spi.annotations.Spi} 注解 value</li>
     *     <li>实现类上的 {@code com.chua.common.support.spi.annotations.Extension} 注解 value</li>
     *     <li>类名去掉接口名后的剩余部分（如 {@code MiniLMEmbeddingClient} 推导为 {@code MiniLM}）</li>
     * </ol>
     *
     * <p>使用示例：</p>
     * <pre>{@code
     * // 指定单个别名
     * @SpiExtension(name = "minilm")
     *
     * // 指定多个别名
     * @SpiExtension(name = {"json", "application/json"})
     * }</pre>
     *
     * @return 扩展名（别名）数组
     */
    String[] name() default {};
}
