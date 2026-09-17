package com.chua.common.support.network.protocol;

import java.util.Map;

/**
* 协议还原接口（SPI）
*
* <p>将网络数据包的原始字节还原为人类可读的文本。
* 通过 SPI 机制支持多种协议（HTTP/Telnet/Email/SSH 等），pcap 模块自动发现并调用。
*
* <h3>使用示例</h3>
* <pre>{@code
*   // 通过 SPI 自动发现所有协议还原器
*   List<ProtocolRestorer> restorers = ServiceProvider.of(ProtocolRestorer.class)
*       .getExtensions();
*
*   // 对数据包尝试还原
*   for (ProtocolRestorer restorer : restorers) {
*       if (restorer.canRestore(protocolInfo, rawData)) {
*           String readable = restorer.restore(protocolInfo, rawData);
*           System.out.println(readable);
*       }
*   }
* }</pre>
*
* @author CH
* @since 2026/07/17
 */
public interface ProtocolRestorer {

    /**
    * 协议名称标识
    *
    * @return 协议名（如 "http", "telnet", "email"）
    */
    String getProtocolName();

    /**
    * 判断是否能还原指定数据包
    *
    * @param protocolInfo 已解析的协议信息（从 Ethernet/IP/TCP 等解析而来）
    * @param rawData      原始数据包字节
    * @return true 表示该还原器可以处理此数据包
    */
    boolean canRestore(Map<String, Object> protocolInfo, byte[] rawData);

    /**
    * 将数据包还原为可读文本
    *
    * @param protocolInfo 已解析的协议信息
    * @param rawData      原始数据包字节
    * @return 人类可读的协议文本
    */
    String restore(Map<String, Object> protocolInfo, byte[] rawData);

    /**
    * 还原的优先级（越小越优先）
    *
    * <p>当多个还原器都能处理同一数据包时，优先级高的先执行。
    * 默认优先级 100。
    *
    * @return 优先级值
    */
    default int getPriority() {
        return 100;
    }
}
