package com.chua.runtime.protocol;

import com.chua.common.support.utils.StringUtils;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * W3C Trace Context 标准实现。
 *
 * <p>遵循 W3C Recommendation: <a href="https://www.w3.org/TR/trace-context/">Trace Context</a>。</p>
 *
 * <p>{@code traceparent} header 格式（version 00）：</p>
 * <pre>
 * version-traceid-parentid-flags
 * 00-{32位hex}-{16位hex}-{2位hex}
 * 例：00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 * </pre>
 *
 * <p>{@code tracestate} header 为厂商扩展，格式 {@code key=value,key2=value2}。
 * 本实现仅支持解析与回写 traceparent，tracestate 可作为可选附加字段透传。</p>
 *
 * <p>与 {@link com.chua.runtime.spy.RuntimeSpy} 集成：</p>
 * <ul>
 *   <li>{@link #inject()} 生成当前线程上下文的 traceparent</li>
 *   <li>{@link #extract(String)} 从上游 header 还原 traceId + parentSpanId，
 *       调用 {@code RuntimeSpy.restore(...)} 后继续传递</li>
 * </ul>
 *
 * @since 4.0.0.42
 */
public final class W3CTraceContext {

    /**
     * traceparent header 名（小写）
     */
    public static final String HEADER_TRACEPARENT = "traceparent";

    /**
     * tracestate header 名（小写）
     */
    public static final String HEADER_TRACESTATE = "tracestate";

    /**
     * 当前版本（00）
     */
    public static final String VERSION = "00";

    /**
     * traceparent 格式正则：version-traceid-parentid-flags（hex）
     */
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    /**
     * 全 0 / 全 f 等非法 traceid 屏蔽（RFC 要求）
     */
    private static final String INVALID_TRACE_ID_1 = "00000000000000000000000000000000";
    /**
     * invalid trace 标识 2
     */
    private static final String INVALID_TRACE_ID_2 = "ffffffffffffffffffffffffffffffff";

    /**
     * 全 0 parentId 屏蔽
     */
    private static final String INVALID_PARENT_ID = "0000000000000000";

    /**
     * 版本
     */
    private final String version;
    /**
     * trace Id
     */
    private final String traceId;
    /**
     * span Id
     */
    private final String spanId;
    /**
     * flags
     */
    private final byte flags;

    private W3CTraceContext(String version, String traceId, String spanId, byte flags) {
        this.version = version;
        this.traceId = traceId;
        this.spanId = spanId;
        this.flags = flags;
    }

    /**
     * 构造当前 Span 的 traceparent header 值（用于 HTTP 出站请求注入）。
     *
     * <p>当前线程追踪上下文通过反射从 {@code com.chua.runtime.spy.RuntimeSpy} 读取，
     * 避免 protocol 模块反向依赖 spy。</p>
     *
     * @return traceparent 字符串；若无追踪上下文则基于随机生成
     */
    public static String inject() {
        String traceId = readCurrentTraceId();
        String spanId = readCurrentSpanId();
        return inject(traceId, spanId, (byte) 0x01);
    }

    /**
     * 通过反射从 {@code com.chua.runtime.spy.RuntimeSpy} 读取当前线程 traceId。
     *
     * @return 当前 traceId，未配置或读取失败返回 null
     */
    private static String readCurrentTraceId() {
        try {
            Class<?> cls = Class.forName("com.chua.runtime.spy.RuntimeSpy");
            return (String) cls.getMethod("getCurrentTraceId").invoke(null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过反射从 {@code com.chua.runtime.spy.RuntimeSpy} 读取当前线程 spanId。
     */
    private static String readCurrentSpanId() {
        try {
            Class<?> cls = Class.forName("com.chua.runtime.spy.RuntimeSpy");
            return (String) cls.getMethod("getCurrentSpanId").invoke(null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过反射调用 {@code com.chua.runtime.spy.RuntimeSpy.restore(...)} 应用追踪上下文。
     */
    private static boolean restoreCurrent(String traceId, String spanId) {
        try {
            Class<?> cls = Class.forName("com.chua.runtime.spy.RuntimeSpy");
            Class<?> snapshotCls = Class.forName("com.chua.runtime.spy.RuntimeSpy$TraceContextSnapshot");
            Class<?> frameCls = Class.forName("com.chua.runtime.spy.RuntimeSpy$TraceStackFrame");
            Object frame = frameCls.getDeclaredConstructor(String.class, String.class)
                    .newInstance(traceId, spanId);
            Object list = java.util.List.of(frame);
            Object snapshot = snapshotCls.getDeclaredConstructor(String.class, java.util.List.class)
                    .newInstance(traceId, list);
            cls.getMethod("restore", snapshotCls).invoke(null, snapshot);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 显式参数构造 traceparent 字符串。
     *
     * @param traceId 32 位 hex traceId（可为 null，自动生成）
     * @param spanId  16 位 hex spanId（可为 null，自动生成）
     * @param flags   8 位 flags（高 6 位预留，低 1 位 sampled）
     * @return traceparent 字符串
     */
    public static String inject(String traceId, String spanId, byte flags) {
        String t = StringUtils.isEmpty(traceId) ? generateTraceId() : traceId;
        String s = StringUtils.isEmpty(spanId) ? generateSpanId() : spanId;
        return String.format("%s-%s-%s-%02x", VERSION, t, s, flags & 0xff);
    }

    /**
     * 从 traceparent header 中解析 W3C Trace Context。
     *
     * <p>解析失败或字段非法（全 0、全 f 等）时返回 null。</p>
     *
     * @param header traceparent header 值
     * @return 解析后的 W3CTraceContext，非法返回 null
     */
    public static W3CTraceContext extract(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // W3C 允许前导空格（多 header 拼接），首个非空字符之前允许空格
        int start = 0;
        while (start < trimmed.length() && trimmed.charAt(start) == ' ') {
            start++;
        }
        if (start >= trimmed.length()) {
            return null;
        }
        trimmed = trimmed.substring(start);
        // 取首个逗号前的内容（多 traceparent 拼接时取首个）
        int comma = trimmed.indexOf(',');
        if (comma >= 0) {
            trimmed = trimmed.substring(0, comma);
        }
        trimmed = trimmed.trim();
        java.util.regex.Matcher m = TRACEPARENT_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            return null;
        }
        String version = m.group(1);
        String traceId = m.group(2);
        String spanId = m.group(3);
        byte flags = (byte) Integer.parseInt(m.group(4), 16);
        if (!isValidTraceId(traceId) || !isValidSpanId(spanId)) {
            return null;
        }
        // 未来版本兼容：按 W3C 规范，对未知版本号解析前两个字段即可
        return new W3CTraceContext(version, traceId, spanId, flags);
    }

    /**
     * 提取后应用到当前线程追踪栈 — 让后续 ENTRY 作为上游 spanId 的子节点。
     *
     * <p>使用反射调用 {@code com.chua.runtime.spy.RuntimeSpy.restore(...)}，
     * 避免 protocol 模块反向依赖 spy。</p>
     *
     * @param header traceparent header 值
     * @return 是否成功应用
     */
    public static boolean extractAndRestore(String header) {
        W3CTraceContext ctx = extract(header);
        if (ctx == null) {
            return false;
        }
        return restoreCurrent(ctx.traceId, ctx.spanId);
    }

    /**
     * 生成 32 位 hex traceId。
     *
     * @return 32 位 hex 字符串
     */
    public static String generateTraceId() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        // UUID 是 32 位 hex，去横线后刚好 32 字符
        return hex;
    }

    /**
     * 生成 16 位 hex spanId。
     *
     * @return 16 位 hex 字符串
     */
    public static String generateSpanId() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, 16);
    }

    /**
     * 校验 traceId 合法性（非全 0、非全 f）。
     */
    private static boolean isValidTraceId(String traceId) {
        if (traceId == null || traceId.length() != 32) {
            return false;
        }
        return !INVALID_TRACE_ID_1.equals(traceId) && !INVALID_TRACE_ID_2.equals(traceId);
    }

    /**
     * 校验 spanId 合法性（非全 0）。
     */
    private static boolean isValidSpanId(String spanId) {
        if (spanId == null || spanId.length() != 16) {
            return false;
        }
        return !INVALID_PARENT_ID.equals(spanId);
    }

    public String getVersion() {
        return version;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public byte getFlags() {
        return flags;
    }

    /**
     * 是否被采样（flags bit 0 = 1）
     */
    public boolean isSampled() {
        return (flags & 0x01) != 0;
    }

    @Override
    public String toString() {
        return String.format("W3C[ver=%s, traceId=%s, spanId=%s, flags=%02x]",
                version, traceId, spanId, flags & 0xff);
    }
}