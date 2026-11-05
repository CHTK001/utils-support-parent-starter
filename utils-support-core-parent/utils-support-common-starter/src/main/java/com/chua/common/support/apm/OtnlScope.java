package com.chua.common.support.apm;

/**
 * Otnl 指标/查询的作用域。
 * <p>
 * SkyWalking / OpenTelemetry 的指标名是「作用域 + 名称」二元组（例如
 * {@code Service + service_resp_time}、{@code Endpoint + endpoint_resp_time}），
 * 因此每条查询都必须带上作用域，不能只给名称。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum OtnlScope {

    /**
     * 全局（不分实体，例如 all_p99）
     */
    ALL,

    /**
     * 服务级
     */
    SERVICE,

    /**
     * 服务实例级
     */
    SERVICE_INSTANCE,

    /**
     * 端点级
     */
    ENDPOINT,

    /**
     * 服务间关系（依赖边）
     */
    SERVICE_RELATION,

    /**
     * 端点间关系
     */
    ENDPOINT_RELATION;

    /**
     * 按名称解析作用域（支持空值、大小写和方括号之外的任何写法）
     * <p>
     * 匹配不上时返回 {@code null}，由 {@link OtnlMetricQuery#scopeOrService()}
     * 归一化到 {@code SERVICE}，避免非预期的调用抛入 500。
     * </p>
     *
     * @param scope 作用域字符名，支持 {@code null}
     * @return 对应作用域，找不到返回 {@code null}
     */
    public static OtnlScope of(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        for (OtnlScope value : values()) {
            if (value.name().equalsIgnoreCase(scope)) {
                return value;
            }
        }
        return null;
    }
}
