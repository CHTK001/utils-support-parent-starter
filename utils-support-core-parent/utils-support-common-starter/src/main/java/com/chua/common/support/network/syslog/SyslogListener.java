package com.chua.common.support.network.syslog;

/**
 * Syslog 消息监听器：服务端每收到一条合法（或兜底包装的）报文即回调一次。
 *
 * <p>回调在服务端 I/O 线程内同步执行，实现应保持轻量；耗时逻辑请自行转交业务线程池。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@FunctionalInterface
public interface SyslogListener {

    /**
     * 收到一条 Syslog 消息。
     *
     * @param message 解析后的消息，不为 null
     */
    void onMessage(SyslogMessage message);
}
