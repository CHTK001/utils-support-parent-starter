package com.chua.common.support.apm.model;

import com.chua.common.support.apm.OtnlTag;

import java.util.List;

/**
 * 服务端点（SkyWalking Endpoint 层实体）。
 *
 * @param id          端点 ID
 * @param serviceName 所属服务
 * @param name        端点名（HTTP 路径 / gRPC 全限定方法名等）
 * @param calls       窗口内调用量（不支持时 -1）
 * @param avgMs       平均耗时毫秒（不支持时 -1）
 * @param p95Ms       P95 毫秒（不支持时 -1）
 * @param p99Ms       P99 毫秒（不支持时 -1）
 * @param errorRate   错误率百分比（不支持时 -1）
 * @param tags        附加属性
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlEndpoint(String id,
                           String serviceName,
                           String name,
                           long calls,
                           double avgMs,
                           double p95Ms,
                           double p99Ms,
                           double errorRate,
                           List<OtnlTag> tags) {

    /**
     * 只带名字的端点
     *
     * @param serviceName 服务名
     * @param name        端点名
     * @return 端点
     */
    public static OtnlEndpoint of(String serviceName, String name) {
        return new OtnlEndpoint(null, serviceName, name, -1, -1, -1, -1, -1, List.of());
    }
}
