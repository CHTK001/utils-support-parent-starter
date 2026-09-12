package com.chua.runtime.protocol;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.StringUtils;
import java.util.UUID;
import java.util.regex.Pattern;

/**
   * W3C 追踪 上下文 标准实现。
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
 * @author CH
 * @since 4.0.0.42
 */
public final class W3CTraceContext {

    /**
      * traceparent 头部 名（小写）
     */
    public static final String HEADER_TRACEPARENT = "traceparent";

    /**
      * tracestate 头部 名（小写）
     */
    public static final String HEADER_TRACESTATE = "tracestate";

    /**
     * 当前版本（00）
     */
    public static final String VERSION = "00";

    /**
      * traceparent 格式正则：版本-traceid-parentid-flags（hex）
     */
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    /**
     * 全 0 / 全 f 等非法 traceid 屏蔽（RFC 要求）
     */
    private static final String INVALID_TRACE_ID_1 = "00000000000000000000000000000000";
    /**
      * invalid 追踪 标识 2
     */
    private static final String INVALID_TRACE_ID_2 = "ffffffffffffffffffffffffffffffff";

    /**
      * 全 0 父id 屏蔽
     */
    private static final String INVALID_PARENT_ID = "0000000000000000";

    /**
     * 版本
     */
    private final String version;
    /**
      * 追踪 标识
     */
    private final String traceId;
    /**
      * span 标识
     */
    private final String spanId;
    /**
     * flags
     */
    private final byte flags;

    /**
      * 创建 W3c追踪上下文 实例
     * @param version 版本
     * @param version 字符串
     * @param version 字符串
     * @param flags byte
     * @param traceId 追踪标识
     * @param spanId spanid
     * @param flags flags
     */
    private W3CTraceContext(String version, String traceId, String spanId, byte flags) {
        this.version = version;
        this.traceId = traceId;
        this.spanId = spanId;
        this.flags = flags;
    }

    /**
      * 构造当前 Span 的 traceparent 头部 值（用于 HTTP 出站请求注入）。
     *
     * <p>当前线程追踪上下文通过反射从 {@code com.chua.runtime.spy.RuntimeSpy} 读取，
      * 避免 协议 模块反向依赖 spy。</p>
     *
     * @return traceparent 字符串；若无追踪上下文则基于随机生成
     */
    public static String inject() {
        String traceId = readCurrentTraceId();
        String spanId = readCurrentSpanId();
        return inject(traceId, spanId, (byte) 0x01);
    }

    /**
      * 通过反射从 {@code com.chua.runtime.spy.RuntimeSpy} 读取当前线程 追踪id。
     *
     * @return 当前 追踪标识，未配置或读取失败返回 空
     */
    private static String readCurrentTraceId() {
        try {
            return (String) ReflectUtils.invokeStatic("com.chua.runtime.spy.RuntimeSpy", "getCurrentTraceId", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
      * 通过反射从 {@code com.chua.runtime.spy.RuntimeSpy} 读取当前线程 spanid。
     * @return 读取当前spanid的结果
     */
    private static String readCurrentSpanId() {
        try {
            return (String) ReflectUtils.invokeStatic("com.chua.runtime.spy.RuntimeSpy", "getCurrentSpanId", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过反射调用 {@code com.chua.runtime.spy.RuntimeSpy.restore(...)} 应用追踪上下文。
     * @param traceId 追踪标识
     * @param spanId spanid
     * @return restore当前的结果
     */
    private static boolean restoreCurrent(String traceId, String spanId) {
        try {
            Class<?> snapshotCls = ReflectUtils.forName("com.chua.runtime.spy.RuntimeSpy$TraceContextSnapshot");
            Class<?> frameCls = ReflectUtils.forName("com.chua.runtime.spy.RuntimeSpy$TraceStackFrame");
            Object frame = ReflectUtils.instantiate(frameCls, traceId, spanId);
            Object list = java.util.List.of(frame);
            Object snapshot = ReflectUtils.instantiate(snapshotCls, traceId, list);
            ReflectUtils.invokeStatic("com.chua.runtime.spy.RuntimeSpy", "restore", void.class, snapshotCls, snapshot);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 显式参数构造 traceparent 字符串。
     *
     * @param traceId 32 位 hex 追踪标识（可为 空，自动生成）
     * @param spanId  16 位 hex spanid（可为 空，自动生成）
     * @param flags   8 位 flags（高 6 位预留，低 1 位 sampled）
     * @return traceparent 字符串
     */
    public static String inject(String traceId, String spanId, byte flags) {
        String t = StringUtils.isEmpty(traceId) ? generateTraceId() : traceId;
        String s = StringUtils.isEmpty(spanId) ? generateSpanId() : spanId;
        return String.format("%s-%s-%s-%02x", VERSION, t, s, flags & 0xff);
    }

    /**
      * 从 traceparent 头部 中解析 W3C 追踪 上下文。
     *
     * <p>解析失败或字段非法（全 0、全 f 等）时返回 null。</p>
     *
     * @param header traceparent 头部 值
     * @return 解析后的 W3c追踪上下文，非法返回 空
     */
    public static W3CTraceContext extract(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
 // W3C 允许前导空格（多 头部 拼接），首个非空字符之前允许空格
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
      * 提取后应用到当前线程追踪栈 — 让后续 ENTRY 作为上游 spanid 的子节点。
     *
     * <p>使用反射调用 {@code com.chua.runtime.spy.RuntimeSpy.restore(...)}，
      * 避免 协议 模块反向依赖 spy。</p>
     *
     * @param header traceparent 头部 值
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
      * 生成 32 位 hex 追踪id。
     *
     * @return 32 位 hex 字符串
     */
    public static String generateTraceId() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        // UUID 是 32 位 hex，去横线后刚好 32 字符
        return hex;
    }

    /**
      * 生成 16 位 hex spanid。
     *
     * @return 16 位 hex 字符串
     */
    public static String generateSpanId() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, 16);
    }

    /**
      * 校验 追踪id 合法性（非全 0、非全 f）。
     * @param traceId 追踪标识
     * @return 是否valid追踪id的结果
     */
    private static boolean isValidTraceId(String traceId) {
        if (traceId == null || traceId.length() != 32) {
            return false;
        }
        return !INVALID_TRACE_ID_1.equals(traceId) && !INVALID_TRACE_ID_2.equals(traceId);
    }

    /**
      * 校验 spanid 合法性（非全 0）。
     * @param spanId spanid
     * @return 是否validspanid的结果
     */
    private static boolean isValidSpanId(String spanId) {
        if (spanId == null || spanId.length() != 16) {
            return false;
        }
        return !INVALID_PARENT_ID.equals(spanId);
    }

    /**
     * 获取版本
     *
     * @return 获取版本的结果
     */
    public String getVersion() {
        return version;
    }

    /**
     * 获取追踪id
     *
     * @return 获取追踪id的结果
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * 获取spanid
     *
     * @return 获取spanid的结果
     */
    public String getSpanId() {
        return spanId;
    }

    /**
     * 获取Flags
     *
     * @return 获取flags的结果
     */
    public byte getFlags() {
        return flags;
    }

    /**
      * 是否被采样（flags 钻头 0 = 1）
     * @return 是否样本的结果
     */
    public boolean isSampled() {
        return (flags & 0x01) != 0;
    }

    @Override
    /** 转为字符串 */
    public String toString() {
        return String.format("W3C[ver=%s, traceId=%s, spanId=%s, flags=%02x]",
                version, traceId, spanId, flags & 0xff);
    }
}