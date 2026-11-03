// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.opentelemetry.support.config;

import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * 采样器约束
 * <p>
 * 把配置里的采样策略字符串转换为 {@link Sampler} 实例，
 * 无法识别的策略会在装配时立即抛出，而不是采样时只取默认值。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class OpenTelemetrySampler {

    /**
     * 未识别采样器时的兜底值
     */
    private static final Sampler DEFAULT = Sampler.alwaysOn();

    /**
     * 无参禁止构造
     */
    private OpenTelemetrySampler() {
    }

    /**
     * 按策略名称创建采样器
     *
     * @param name  策略名, 不区分大小写
     * @param ratio 采样概率, trace_id_ratio 策略时有效
     * @return 采样器
     */
    public static Sampler of(String name, double ratio) {
        if (name == null || name.trim().isEmpty()) {
            return DEFAULT;
        }
        String policy = name.trim().toLowerCase();
        switch (policy) {
            case "always_on":
                return Sampler.alwaysOn();
            case "always_off":
                return Sampler.alwaysOff();
            case "parent_based":
                return Sampler.parentBased(Sampler.alwaysOn());
            case "trace_id_ratio":
                if (ratio < 0.0 || ratio > 1.0) {
                    throw new IllegalArgumentException("OpenTelemetry 采样概率必须在 0.0~1.0 之间, 实际: " + ratio);
                }
                return Sampler.traceIdRatioBased(ratio);
            default:
                throw new IllegalArgumentException("不支持的 OpenTelemetry 采样器: " + name);
        }
    }
}
