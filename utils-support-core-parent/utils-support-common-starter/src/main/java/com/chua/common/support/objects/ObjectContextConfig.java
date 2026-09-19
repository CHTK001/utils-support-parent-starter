package com.chua.common.support.objects;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 对象上下文 配置，控制 SPI 发现、注解扫描等行为。
 *
 * <p>默认通过 {@link #defaults()} 从 classpath 的 {@code application.yml} /
 * {@code application.yaml} / {@code application.properties} 读取（对齐 Spring Boot）。</p>
 *
 * <p>YAML 示例：
 * <pre>
 * object-context:
 *   spi-enabled: true
 *   annotation-scan-enabled: true
 *   scan-packages:
 *     - com.example.app
 * </pre>
 * </p>
 *
 * @author CH
 * @since 2026/07/16
 */
@Getter
@Builder
public class ObjectContextConfig {

    /**
     * 是否启用 SPI 作为 Beandefinition注册 的发现器，默认 true
     */
    @Builder.Default
    /** SPI是否启用 */
    private boolean spiEnabled = true;

    /**
    * 是否启用注解/包扫描，默认 false；yml 中配置了 扫描-包 时自动为 true
    */
    @Builder.Default
    /** Annotationscan是否启用 */
    private boolean annotationScanEnabled = false;

    /**
    * 需要扫描的基包路径，注解扫描已启用=true 或列表非空时生效
    */
    @Builder.Default
    /** Scanpackages */
    private List<String> scanPackages = Collections.emptyList();

    /**
    * 从 类路径 配置文件加载默认配置（application.yml / yaml / 属性）。
    *
    * @return 配置实例
    */
    public static ObjectContextConfig defaults() {
        return ObjectContextConfigLoader.load();
    }

    /**
     * 不读取 yml，仅使用代码内置默认值。
     *
     * @return 内置默认配置
     */
    public static ObjectContextConfig empty() {
        return ObjectContextConfig.builder().build();
    }

    /**
     * 是否应在 初始化 时执行包扫描。
     *
     * @return true 表示需要扫描
     */
    public boolean shouldScan() {
        return (annotationScanEnabled || (scanPackages != null && !scanPackages.isEmpty()))
                && scanPackages != null
                && !scanPackages.isEmpty();
    }
}
