// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.agent.opencode;

import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * opencode server 的 API 契约版本。
 *
 * <p>opencode 的 v2 接口在 2.x 做了三处互不兼容的调整，适配层必须按 server 实际版本
 * 选择契约，不能靠猜：</p>
 *
 * <table border="1">
 *   <caption>1.19.x 与 2.x 的差异</caption>
 *   <tr><th>能力</th><th>1.19.x（{@link #LEGACY}）</th><th>2.x（{@link #V2}）</th></tr>
 *   <tr><td>会话事件流</td><td>{@code GET /api/session/{id}/event?after=seq}</td>
 *       <td>{@code GET /api/event}（全局、按 types 过滤）</td></tr>
 *   <tr><td>事件名</td><td>{@code session.next.text.delta}</td>
 *       <td>{@code session.text.delta}</td></tr>
 *   <tr><td>发消息</td><td>{@code {"prompt":{"text":..}}}</td><td>{@code {"text":..}}</td></tr>
 *   <tr><td>审批回传</td><td>{@code {"reply":"once"}}</td><td>{@code {"decision":"once"}}</td></tr>
 *   <tr><td>结构化提问</td><td>{@code question.v2.asked} + {@code /question/{id}/reply}</td>
 *       <td>{@code form.created} + {@code /form/{id}/reply}</td></tr>
 *   <tr><td>健康探测</td><td>{@code /global/health} 返回 JSON</td><td>{@code /api/info} 返回 JSON</td></tr>
 * </table>
 *
 * <p>版本从 {@code GET /api/info} 的 {@code version} 字段读取；1.19.x 没有该接口，
 * 再以 {@code /global/health} 的 JSON 契约确认。两者都不可用时抛
 * {@link IllegalStateException}，不做「猜一个」的降级。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum OpencodeApiProfile {

    /**
     * 1.19.x 事件契约：会话级持久事件流，事件名带 {@code .next.} 段。
     */
    LEGACY,

    /**
     * 2.x 事件契约：全局事件流，事件名不带 {@code .next.} 段。
     */
    V2;

    /**
     * 版本探测接口（仅 2.x 提供）
     */
    private static final String INFO_PATH = "/api/info";

    /**
     * 1.x 健康接口
     */
    private static final String LEGACY_HEALTH_PATH = "/global/health";

    /**
     * 版本字段名
     */
    private static final String FIELD_VERSION = "version";

    /**
     * 健康字段名
     */
    private static final String FIELD_HEALTHY = "healthy";

    /**
     * 1.x 版本号前缀
     */
    private static final String VERSION_PREFIX_V1 = "1.";

    /**
     * 每个 server 基地址只探测一次
     */
    private static final Map<String, OpencodeApiProfile> CACHE = new ConcurrentHashMap<>();

    /**
     * 是否为 2.x 契约。
     *
     * @return true 表示按 2.x 契约调用
     */
    public boolean isV2() {
        return this == V2;
    }

    /**
     * 探测并缓存 server 的契约版本。
     *
     * @param client 已绑定鉴权的客户端
     * @return 契约版本
     */
    public static OpencodeApiProfile detect(OpencodeHttpClient client) {
        String key = client.baseUrl();
        OpencodeApiProfile cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        OpencodeApiProfile detected = probe(client);
        CACHE.put(key, detected);
        return detected;
    }

    /**
     * 清理探测缓存（server 重启换版本后调用）。
     *
     * @param baseUrl 基地址；为空表示全部清理
     */
    public static void invalidate(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            CACHE.clear();
        } else {
            CACHE.remove(baseUrl);
        }
    }

    /**
     * 实际探测。
     *
     * @param client 客户端
     * @return 契约版本
     */
    private static OpencodeApiProfile probe(OpencodeHttpClient client) {
        RuntimeException infoFailure = null;
        try {
            JsonNode info = client.getJson(INFO_PATH);
            String version = info.path(FIELD_VERSION).asText("");
            if (StringUtils.isNotBlank(version)) {
                // 只有 2.x 提供 /api/info；版本串仍以 1. 开头时按旧契约处理
                return version.startsWith(VERSION_PREFIX_V1) ? LEGACY : V2;
            }
            return V2;
        } catch (RuntimeException e) {
            infoFailure = e;
        }
        try {
            JsonNode health = client.getJson(LEGACY_HEALTH_PATH);
            if (health.has(FIELD_HEALTHY)) {
                return LEGACY;
            }
        } catch (RuntimeException ignored) {
            // 两条探测都不成立时统一抛下面的异常
        }
        throw new IllegalStateException(
                "无法识别 opencode server 的 API 版本：/api/info 与 /global/health 均未返回可解析的 JSON，"
                        + "请确认 server 版本（1.19.x 或 2.x）以及 " + client.baseUrl() + " 是否为本项目的 opencode server",
                infoFailure);
    }
}
