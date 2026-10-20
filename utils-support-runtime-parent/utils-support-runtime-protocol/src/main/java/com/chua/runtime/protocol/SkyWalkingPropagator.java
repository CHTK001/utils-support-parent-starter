package com.chua.runtime.protocol;

import com.chua.common.support.utils.StringUtils;

import java.util.Map;
import java.util.UUID;

/**
 * SkyWalking 兼容传播器 — 生成/解析 {@code sw8} 链路头。
 *
 * <p>SkyWalking 8.x 跨进程传播 header：</p>
 * <pre>
 * sw8: 1-base64(traceId)-base64(parentService)-base64(parentInstance)-
 *      base64(parentEndpoint)-base64(parentAddress)-base64(entryEndpoint)-
 *      1-6(traceSegmentId + spanId + sampled + service)
 * </pre>
 *
 * <p>本实现仅保留核心字段，与 W3C traceparent 双写，保证跨 Agent 链路与
 * SkyWalking 生态（上游/下游）均可串联。</p>
 *
 * @author CH
 * @since 4.1.0
 */
public final class SkyWalkingPropagator {

    /**
     * sw8 header 名。
     */
    public static final String HEADER_SW8 = "sw8";

    /**
     * 本服务名（默认 hotsport-agent）。
     */
    private static volatile String serviceName = "hotsport-agent";

    /**
     * 本实例名（默认 pid@hostname）。
     */
    private static volatile String instanceName = defaultInstanceName();

    /**
     * 本服务入口端点（默认 /agent）。
     */
    private static volatile String endpointName = "/agent";

    /**
     * 私有构造。
     */
    private SkyWalkingPropagator() {
    }

    /**
     * 设置服务名。
     *
     * @param serviceName 服务名
     */
    public static void setServiceName(String serviceName) {
        if (StringUtils.isNotEmpty(serviceName)) {
            SkyWalkingPropagator.serviceName = serviceName;
        }
    }

    /**
     * 设置实例名。
     *
     * @param instanceName 实例名
     */
    public static void setInstanceName(String instanceName) {
        if (StringUtils.isNotEmpty(instanceName)) {
            SkyWalkingPropagator.instanceName = instanceName;
        }
    }

    /**
     * 设置端点名。
     *
     * @param endpointName 端点名
     */
    public static void setEndpointName(String endpointName) {
        if (StringUtils.isNotEmpty(endpointName)) {
            SkyWalkingPropagator.endpointName = endpointName;
        }
    }

    /**
     * 生成当前线程 sw8 header（基于 RuntimeSpy 当前 traceId/spanId，失败则随机）。
     *
     * @return sw8 header 值
     */
    public static String inject() {
        String traceId = readCurrentTraceId();
        String spanId = readCurrentSpanId();
        String fullTraceId = StringUtils.isEmpty(traceId) ? W3CTraceContext.generateTraceId() : traceId;
        String fullSpanId = StringUtils.isEmpty(spanId) ? W3CTraceContext.generateSpanId() : spanId;
        String segmentId = "segment-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return String.join("-",
                "1",
                base64(fullTraceId),
                base64(serviceName),
                base64(instanceName),
                base64(endpointName),
                base64(""),
                base64(endpointName),
                "1",
                base64(segmentId + "|" + fullSpanId + "|1|" + serviceName));
    }

    /**
     * 解析 sw8 header，返回可用的 W3C traceId/spanId。
     *
     * @param header sw8 header 值
     * @return 解析结果（traceId/spanId）；无效返回 null
     */
    public static Sw8Context extract(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String[] parts = header.split("-", -1);
        if (parts.length < 9) {
            return null;
        }
        try {
            String traceId = decode(parts[1]);
            String last = decode(parts[parts.length - 1]);
            String[] lastParts = last.split("\\|", -1);
            String spanId = lastParts.length >= 2 ? lastParts[1] : null;
            if (StringUtils.isEmpty(traceId) || StringUtils.isEmpty(spanId)) {
                return null;
            }
            return new Sw8Context(traceId, spanId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 header 提取并恢复当前线程追踪上下文。
     *
     * @param header sw8 header 值
     * @return 是否恢复成功
     */
    public static boolean extractAndRestore(String header) {
        Sw8Context ctx = extract(header);
        if (ctx == null) {
            return false;
        }
        return W3CTraceContext.extractAndRestore(W3CTraceContext.inject(ctx.getTraceId(), ctx.getSpanId(), (byte) 0x01));
    }

    /**
     * 注入到 header 集合（保留原键大小写）。
     *
     * @param headers header 集合
     */
    public static void inject(Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        headers.put(HEADER_SW8, inject());
    }

    /**
     * 从 header 集合提取 sw8 并恢复当前上下文。
     *
     * @param headers header 集合
     * @return 是否恢复成功
     */
    public static boolean extract(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(HEADER_SW8) && StringUtils.isNotEmpty(e.getValue())) {
                return extractAndRestore(e.getValue());
            }
        }
        return false;
    }

    /**
     * 读取当前 traceId（反射，避免依赖 spy 模块）。
     *
     * @return traceId
     */
    private static String readCurrentTraceId() {
        try {
            return (String) com.chua.common.support.reflection.ReflectUtils.invokeStatic(
                    "com.chua.runtime.spy.RuntimeSpy", "getCurrentTraceId", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取当前 spanId。
     *
     * @return spanId
     */
    private static String readCurrentSpanId() {
        try {
            return (String) com.chua.common.support.reflection.ReflectUtils.invokeStatic(
                    "com.chua.runtime.spy.RuntimeSpy", "getCurrentSpanId", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Base64 URL-safe 编码。
     *
     * @param s 原始字符串
     * @return 编码结果
     */
    private static String base64(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Base64 URL-safe 解码。
     *
     * @param s 编码字符串
     * @return 解码结果
     */
    private static String decode(String s) {
        return new String(java.util.Base64.getUrlDecoder().decode(s), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 默认实例名。
     *
     * @return pid@hostname
     */
    private static String defaultInstanceName() {
        try {
            long pid = ProcessHandle.current().pid();
            String host = java.net.InetAddress.getLocalHost().getHostName();
            return pid + "@" + host;
        } catch (Exception e) {
            return "unknown@host";
        }
    }

    /**
     * SkyWalking sw8 解析结果。
     */
    public static class Sw8Context {
        private final String traceId;
        private final String spanId;

        /**
         * 构造解析结果。
         *
         * @param traceId traceId
         * @param spanId  spanId
         */
        public Sw8Context(String traceId, String spanId) {
            this.traceId = traceId;
            this.spanId = spanId;
        }

        /**
         * 获取 traceId。
         *
         * @return traceId
         */
        public String getTraceId() {
            return traceId;
        }

        /**
         * 获取 spanId。
         *
         * @return spanId
         */
        public String getSpanId() {
            return spanId;
        }
    }
}
