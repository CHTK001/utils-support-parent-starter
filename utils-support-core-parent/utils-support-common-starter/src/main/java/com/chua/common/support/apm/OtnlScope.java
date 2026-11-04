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
    ENDPOINT_RELATION
}
