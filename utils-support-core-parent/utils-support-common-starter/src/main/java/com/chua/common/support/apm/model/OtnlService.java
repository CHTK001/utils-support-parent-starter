package com.chua.common.support.apm.model;

import com.chua.common.support.apm.OtnlTag;

import java.util.List;

/**
 * 服务（SkyWalking Service 层实体）。
 *
 * @param id        服务 ID（引擎侧标识，可空）
 * @param name      服务名
 * @param group     服务组（可空）
 * @param layers    分层（SkyWalking 10 起有 MESH / GENERAL / VIRTUAL_DATABASE …）
 * @param instances 实例数
 * @param endpoints 端点数
 * @param calls     窗口内调用量（引擎不支持时填 -1）
 * @param avgMs     平均耗时毫秒（引擎不支持时填 -1）
 * @param p95Ms     P95 毫秒（不支持填 -1）
 * @param p99Ms     P99 毫秒（不支持填 -1）
 * @param errorRate 错误率百分比（不支持填 -1）
 * @param tags      附加属性
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlService(String id,
                          String name,
                          String group,
                          List<String> layers,
                          int instances,
                          int endpoints,
                          long calls,
                          double avgMs,
                          double p95Ms,
                          double p99Ms,
                          double errorRate,
                          List<OtnlTag> tags) {

    /**
     * 只带名字的服务（引擎只返回目录时使用）
     *
     * @param name 服务名
     * @return 服务
     */
    public static OtnlService of(String name) {
        return new OtnlService(null, name, null, List.of(), 0, 0, -1, -1, -1, -1, -1, List.of());
    }
}
