package com.chua.common.support.ai.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上游响应头捕获（按线程）。
 *
 * <p>供应商配置了「透传响应头」时，客户端会把上游返回的指定响应头放进这里；
 * 上层（网关/控制器）在调用返回后立刻取走，写回自己给调用方的 HTTP 响应。
 * 只对同步调用有意义：SSE 流式的响应头在开流时已经发出，无法再补。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public final class ResponseHeaderCapture {

    /**
     * 线程内暂存的响应头（同名只留第一个）
     */
    private static final ThreadLocal<Map<String, String>> HOLDER = new ThreadLocal<>();

    private ResponseHeaderCapture() {
    }

    /**
     * 开始捕获：清掉同一线程上一次的残留。
     */
    public static void begin() {
        HOLDER.set(new LinkedHashMap<>(8));
    }

    /**
     * 记录一个响应头。
     *
     * @param name  头名
     * @param value 头值
     */
    public static void put(String name, String value) {
        Map<String, String> holder = HOLDER.get();
        if (holder != null && name != null && value != null) {
            holder.putIfAbsent(name, value);
        }
    }

    /**
     * 取走并清空当前线程的捕获结果。
     *
     * @return 响应头；未捕获时为空表
     */
    public static Map<String, String> drain() {
        Map<String, String> holder = HOLDER.get();
        HOLDER.remove();
        return holder == null ? Map.of() : holder;
    }
}
