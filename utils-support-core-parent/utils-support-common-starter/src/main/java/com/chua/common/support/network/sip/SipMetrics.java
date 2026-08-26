package com.chua.common.support.network.sip;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SIP 服务器/客户端运行时指标（轻量级、无外部依赖）。
 *
 * <p>提供关键计数用于：</p>
 * <ul>
 *   <li>运行时健康监控（活跃隧道/客户端/帧率）</li>
 *   <li>故障定位（错误分类计数）</li>
 *   <li>容量规划（认证/注册/隧道累计）</li>
 * </ul>
 *
 * <p>使用 {@link ConcurrentHashMap} 支持并发安全的多维度错误计数，
 * 指标读取使用 {@link #snapshot()} 返回一致性弱快照。</p>
 */
public class SipMetrics {

    private static final SipMetrics INSTANCE = new SipMetrics();

    public static SipMetrics get() { return INSTANCE; }

    // 服务端计数器
    private final AtomicLong authAcceptTotal = new AtomicLong();
    private final AtomicLong authRejectTotal = new AtomicLong();
    private final AtomicLong serviceRegisterTotal = new AtomicLong();
    private final AtomicLong serviceUnregisterTotal = new AtomicLong();
    private final AtomicLong tunnelOpenTotal = new AtomicLong();
    private final AtomicLong tunnelCloseTotal = new AtomicLong();
    private final AtomicLong errorCloseTotal = new AtomicLong();
    private final AtomicLong frameBytesIn = new AtomicLong();
    private final AtomicLong frameBytesOut = new AtomicLong();
    private final AtomicLong muxFlushTotal = new AtomicLong();
    private final AtomicLong muxEarlyFrameBufferedTotal = new AtomicLong();
    private final AtomicLong muxCloseMarkerTotal = new AtomicLong();

    private final AtomicInteger activeTunnels = new AtomicInteger();
    private final AtomicInteger activeClients = new AtomicInteger();

    /** 错误分类计数（key = 错误类别，例 "auth.bad_sig"/"mux.unknown_channel"） */
    private final Map<String, AtomicLong> errorsByCategory = new ConcurrentHashMap<>();

    public void onAuthAccept() { authAcceptTotal.incrementAndGet(); }
    public void onAuthReject(String category) { authRejectTotal.incrementAndGet(); incError(category); }
    public void onServiceRegister() { serviceRegisterTotal.incrementAndGet(); }
    public void onServiceUnregister() { serviceUnregisterTotal.incrementAndGet(); }
    public void onTunnelOpen() {
        tunnelOpenTotal.incrementAndGet();
        activeTunnels.incrementAndGet();
    }
    public void onTunnelClose(String reason) {
        tunnelCloseTotal.incrementAndGet();
        activeTunnels.decrementAndGet();
        if ("error".equals(reason)) errorCloseTotal.incrementAndGet();
    }
    public void onClientConnect() { activeClients.incrementAndGet(); }
    public void onClientDisconnect() { activeClients.decrementAndGet(); }
    public void onFrameBytesIn(long n) { if (n > 0) frameBytesIn.addAndGet(n); }
    public void onFrameBytesOut(long n) { if (n > 0) frameBytesOut.addAndGet(n); }
    public void onMuxFlush(int frames) {
        if (frames > 0) muxFlushTotal.addAndGet(frames);
    }
    public void onMuxEarlyFrameBuffered() { muxEarlyFrameBufferedTotal.incrementAndGet(); }
    public void onMuxCloseMarker() { muxCloseMarkerTotal.incrementAndGet(); }
    public void incError(String category) {
        if (category == null || category.isEmpty()) return;
        errorsByCategory.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * 返回当前指标的弱一致性快照（用于日志或 /metrics 端点）。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auth_accept_total", authAcceptTotal.get());
        m.put("auth_reject_total", authRejectTotal.get());
        m.put("service_register_total", serviceRegisterTotal.get());
        m.put("service_unregister_total", serviceUnregisterTotal.get());
        m.put("tunnel_open_total", tunnelOpenTotal.get());
        m.put("tunnel_close_total", tunnelCloseTotal.get());
        m.put("tunnel_close_error_total", errorCloseTotal.get());
        m.put("active_tunnels", activeTunnels.get());
        m.put("active_clients", activeClients.get());
        m.put("frame_bytes_in", frameBytesIn.get());
        m.put("frame_bytes_out", frameBytesOut.get());
        m.put("mux_flush_total", muxFlushTotal.get());
        m.put("mux_early_frame_buffered_total", muxEarlyFrameBufferedTotal.get());
        m.put("mux_close_marker_total", muxCloseMarkerTotal.get());
        Map<String, Long> errs = new LinkedHashMap<>();
        errorsByCategory.forEach((k, v) -> errs.put(k, v.get()));
        m.put("errors_by_category", errs);
        return m;
    }

    /**
     * 将指标格式化为单行结构化日志条目（key=value 用空格分隔）。
     *
     * @param prefix 日志前缀（如 "sip-metrics"）
     * @return 单行字符串
     */
    public String formatOneLine(String prefix) {
        Map<String, Object> s = snapshot();
        StringBuilder sb = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) {
            sb.append(prefix);
        }
        s.forEach((k, v) -> {
            if (v instanceof Map) return;
            sb.append(' ').append(k).append('=').append(v);
        });
        @SuppressWarnings("unchecked")
        Map<String, Long> errs = (Map<String, Long>) s.get("errors_by_category");
        if (errs != null && !errs.isEmpty()) {
            sb.append(' ').append("errors");
            errs.forEach((k, v) -> sb.append('[').append(k).append('=').append(v).append(']'));
        }
        return sb.toString();
    }
}
