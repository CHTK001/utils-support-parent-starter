// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.opentelemetry.support.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenTelemetry 配置属性
 * <p>
 * 前缀 {@code opentelemetry}, 自动装配时用于创建默认 {@link com.chua.opentelemetry.support.client.OpenTelemetryClient}
 * 与 {@link com.chua.opentelemetry.support.engine.OpenTelemetryEngine}。
 * </p>
 * <h3>策略</h3>
 * <ul>
 *   <li>{@code opentelemetry.sampler}：always_on / always_off / parent_based / trace_id_ratio</li>
 *   <li>{@code opentelemetry.ratio}：trace_id_ratio 时使用，0.0 ~ 1.0（缺省 1.0）</li>
 *   <li>{@code opentelemetry.url}：已归档，当前版本内存导出 Span，OTA 升级后用于 OTLP 推送</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Data
@ConfigurationProperties(prefix = "opentelemetry")
public class OpenTelemetryProperties {

    /**
     * 是否启用
     */
    private boolean enabled = true;

    /**
     * 服务名称, 用于 {@link com.chua.opentelemetry.support.client.OpenTelemetryClient}
     * 内部 {@code InstrumentationScopeInfo} 的标识
     */
    private String serviceName = "unknown-service";

    /**
     * 采样策略, 详见 {@link OpenTelemetrySampler#of(String, double)}
     */
    private String sampler = "always_on";

    /**
     * 采样概率, {@code trace_id_ratio} 策略时有效
     */
    private double ratio = 1.0;

    /**
     * OTLP/日志接收地址, 当前版本作参数占位, 实际 Span 缓存到内存
     */
    private String url;
}
