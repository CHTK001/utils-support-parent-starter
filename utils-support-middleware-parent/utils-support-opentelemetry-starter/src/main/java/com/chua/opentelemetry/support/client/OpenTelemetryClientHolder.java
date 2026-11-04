package com.chua.opentelemetry.support.client;

/**
 * OpenTelemetry 客户端进程级持有者。
 * <p>
 * {@code OtnlEngine} 实现是 SPI 无参构造出来的，拿不到 Spring Bean；但 Span 数据又只能
 * 来自应用真正在用的那一个 {@link OpenTelemetryClient}（多个实例各自持有独立
 * {@code TracerProvider}，Span 不互通）。因此由 {@code OpenTelemetryAutoConfiguration}
 * 在创建客户端后登记到这里，SPI 引擎再来取。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class OpenTelemetryClientHolder {

    private static volatile OpenTelemetryClient client;

    private OpenTelemetryClientHolder() {
    }

    /**
     * 登记客户端
     *
     * @param newClient 客户端
     */
    public static void register(OpenTelemetryClient newClient) {
        client = newClient;
    }

    /**
     * 当前客户端
     *
     * @return 客户端，未装配返回 null
     */
    public static OpenTelemetryClient get() {
        return client;
    }

    /**
     * 注销客户端（容器关闭时）
     *
     * @param removed 待注销的客户端，null 表示无条件清空
     */
    public static void unregister(OpenTelemetryClient removed) {
        if (removed == null || client == removed) {
            client = null;
        }
    }
}
