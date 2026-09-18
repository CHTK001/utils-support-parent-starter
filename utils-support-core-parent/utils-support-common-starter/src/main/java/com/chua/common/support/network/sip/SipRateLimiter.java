package com.chua.common.support.network.sip;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SIP 速率限制器（轻量级滑动窗口）。
 *
 * <p>两类规则：</p>
 * <ul>
 *   <li><b>帧速率</b>：单连接最小帧间隔（纳秒），用于防洪水攻击；默认 100µs ≈ 10000 frames/s/conn</li>
 *   <li><b>认证速率</b>：单源 IP 每分钟最大 AUTH 次数（防爆破），0 表示禁用</li>
 * </ul>
*/
public class SipRateLimiter {

    /**
    * 帧速率：每连接上次帧纳秒时间戳（key = SignalConnection 引用或 clientId）
    */
    private final ConcurrentHashMap<Object, AtomicLong> lastFrameNs = new ConcurrentHashMap<>();
    private final long minFrameIntervalNs;

    /**
    * 认证速率：key = 源 IP，value = 滑动窗口的纳秒时间戳数组（容量 = 1 分钟内最大次数）
    */
    private final ConcurrentHashMap<String, long[]> authTimestamps = new ConcurrentHashMap<>();
    private final int maxAuthPerIpPerMin;
    private final long windowNs = 60_000_000_000L; // 1 分钟

    /**
     * 构造方法，创建 Sip速率Limiter 实例。
     *
     * @param minFrameIntervalNs 最小值Frame间隔Ns，不允许为 null
     * @param maxAuthPerIpPerMin 最大值AuthPerIPPer最小值，不允许为 null
     */
    public SipRateLimiter(long minFrameIntervalNs, int maxAuthPerIpPerMin) {
        this.minFrameIntervalNs = minFrameIntervalNs;
        this.maxAuthPerIpPerMin = maxAuthPerIpPerMin;
    }

    /**
    * 检查是否允许一帧（基于上次接受帧的时间）。返回 true=允许，false=超速。
    * @param connKey 连接键，不允许为 null
    * @return 是否成功（true 表示成功）
    */
    public boolean allowFrame(Object connKey) {
        if (minFrameIntervalNs <= 0) {
            return true;
        }
        long now = System.nanoTime();
        AtomicLong last = lastFrameNs.computeIfAbsent(connKey, k -> new AtomicLong(0));
        long prev = last.get();
        if (prev == 0 || now - prev >= minFrameIntervalNs) {
            if (prev == 0 || last.compareAndSet(prev, now)) {
                return true;
            }
            return allowFrame(connKey); // CAS 失败重试一次
        }
        return false;
    }

    /**
    * 检查源 IP 的 AUTH 频率（每分钟 maxAuthPerIpPerMin 次）。
    * @param sourceIp 来源IP，不允许为 null
    * @return 是否成功（true 表示成功）
    */
    public boolean allowAuth(String sourceIp) {
        if (maxAuthPerIpPerMin <= 0) {
            return true;
        }
        long[] arr = authTimestamps.computeIfAbsent(sourceIp, k -> new long[maxAuthPerIpPerMin]);
        long now = System.nanoTime();
        synchronized (arr) {
            // 滑动清理过期戳
            int writeIdx = 0;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] != 0 && now - arr[i] >= windowNs) {
                    continue;
                }
                arr[writeIdx++] = arr[i];
            }
            // 找首个空位（0）
            int slot = -1;
            for (int i = writeIdx; i < arr.length; i++) {
                arr[i] = 0;
            }
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == 0) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                // 窗口已满
                return false;
            }
            arr[slot] = now;
            return true;
        }
    }

    /**
    * 释放资源（连接断开时调用）。
    * @param connKey 连接键，不允许为 null
    */
    public void releaseConnection(Object connKey) {
        lastFrameNs.remove(connKey);
    }

    /**
    * 从 Socket 远端地址提取 IP（去除端口）。
    * @param s 方法入参 s
    * @return 结果字符串
    */
    public static String ipOf(java.net.Socket s) {
        if (s == null) {
            return "?";
        }
        InetSocketAddress a = (InetSocketAddress) s.getRemoteSocketAddress();
        return a == null ? "?" : a.getAddress().getHostAddress();
    }
}
