package com.chua.gateway.server.api;

/**
 * 鉴权请求体（来自前端的 3 步流程第 2 步）。
 *
 * <p>支持两种模式：
 *   <ul>
 *     <li>key 模式：仅 {@code mode=key}，使用 {@code key} 字段查找预配置</li>
 *     <li>custom 模式：{@code mode=custom}，使用 protocol+host+port+user+password 自定义</li>
 *   </ul>
 * </p>
 *
 * @param mode      "key" | "custom"
 * @param key       仅 key 模式使用
 * @param protocol  custom 模式使用
 * @param host      custom 模式使用
 * @param port      custom 模式使用
 * @param user      custom 模式使用
 * @param password  custom 模式使用
 * @author CH
 * @since 4.0.0.42
 */
public record AuthRequest(
        String mode,
        String key,
        String protocol,
        String host,
        Integer port,
        String user,
        String password
) {
}
