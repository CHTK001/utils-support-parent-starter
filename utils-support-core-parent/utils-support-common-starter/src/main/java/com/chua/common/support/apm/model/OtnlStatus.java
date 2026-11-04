package com.chua.common.support.apm.model;

import java.util.List;

/**
 * 引擎探测状态。
 * <p>
 * 前端「有没有 APM」的唯一判据：{@link #available()} 为 true 表示当前 JVM
 * 里存在可用的 APM 引擎；{@link #message()} 说明不可用的原因（未装 agent、
 * OAP 连不上、配置关闭等），直接回显给用户，避免出现空白页。
 * </p>
 *
 * @param name         引擎标识（skywalking / opentelemetry / hotspot）
 * @param version      引擎/后端版本，未知填 {@code unknown}
 * @param available    是否可用
 * @param enabled      是否被配置启用（false 表示用户主动关闭）
 * @param endpoint     后端地址（进程内引擎填 {@code local}）
 * @param mode         引擎运行模式：agent（进程内）/ remote（远端后端）
 * @param capabilities 能力清单，见 {@link com.chua.common.support.apm.OtnlEngine#capabilities()}
 * @param message      说明/失败原因
 * @param checkedAt    探测时间毫秒戳
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlStatus(String name,
                         String version,
                         boolean available,
                         boolean enabled,
                         String endpoint,
                         String mode,
                         List<String> capabilities,
                         String message,
                         long checkedAt) {

    /**
     * 构建「不可用」状态
     *
     * @param name    引擎标识
     * @param message 原因
     * @return 状态
     */
    public static OtnlStatus unavailable(String name, String message) {
        return new OtnlStatus(name, "unknown", false, false, null, null, List.of(),
                message, System.currentTimeMillis());
    }

    /**
     * 构建「可用」状态
     *
     * @param name         引擎标识
     * @param version      版本
     * @param endpoint     后端地址
     * @param mode         运行模式
     * @param capabilities 能力清单
     * @return 状态
     */
    public static OtnlStatus available(String name, String version, String endpoint,
                                       String mode, List<String> capabilities) {
        return new OtnlStatus(name, version, true, true, endpoint, mode, capabilities,
                "ok", System.currentTimeMillis());
    }
}
