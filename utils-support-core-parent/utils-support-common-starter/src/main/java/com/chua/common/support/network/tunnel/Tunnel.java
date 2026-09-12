package com.chua.common.support.network.tunnel;

import java.io.Closeable;
import java.util.function.Consumer;

/**
* 隧道接口，支持开启（返回隧道端口）、关闭、实时信息回调。
* <p>
* 隧道用于在网络层建立端口转发，常见场景：
* <ul>
*   <li>{@code SSH 本地转发}：将本地端口流量转发到远程主机端口</li>
*   <li>{@code SSH 远程转发}：将远程端口流量转发到本地主机端口</li>
*   <li>{@code SSH 动态转发}：本地 SOCKS5 代理，所有流量经 SSH 转发</li>
* </ul>
* </p>
* <p>
* 使用示例：
* <pre>{@code
* Tunnel tunnel = TunnelProvider.of("sshz")
*     .host("jump.example.com")
*     .port(22)
*     .username("root")
*     .password("pass")
*     .forward()
*     .local(0, "127.0.0.1", 3306)
*     .build();
*
* int localPort = tunnel.open();
* tunnel.onInfo(info -> log.info("隧道状态: {}, 本地端口: {}", info.status(), info.localPort()));
*
* // 使用隧道...
*
* tunnel.close();
* }</pre>
* </p>
*
* @author CH
* @since 2026/07/31
 */
public interface Tunnel extends Closeable {

    /**
    * 开启隧道。
    * <p>如果指定本地端口为 0，系统自动分配可用端口，并返回实际分配的端口号。</p>
    *
    * @return 实际绑定的本地端口号
    * @throws TunnelException 隧道开启失败
     */
    int open();

    /**
    * 关闭隧道，释放所有资源。
     */
    @Override
    void close();

    /**
    * 获取隧道实时信息。
    *
    * @return 隧道信息快照
     */
    TunnelInfo getInfo();

    /**
    * 设置实时信息回调。
    * <p>当隧道状态发生变化时触发回调，例如：开启成功、关闭、错误等。</p>
    *
    * @param callback 回调函数，接收隧道信息
     */
    void onInfo(Consumer<TunnelInfo> callback);

    /**
    * 隧道是否处于开启状态。
    *
    * @return true 表示已开启
     */
    boolean isOpen();
}
