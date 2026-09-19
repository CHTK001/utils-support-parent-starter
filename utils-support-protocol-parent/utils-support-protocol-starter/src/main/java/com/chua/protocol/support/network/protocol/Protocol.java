package com.chua.protocol.support.network.protocol;

import com.chua.protocol.support.network.protocol.server.ProtocolServer;

/**
 * 协议工厂 SPI 接口。
 *
 * <p>每个协议实现（http / armeria / kcp / rsocket）通过 {@code @Spi} 注册扩展名，
 * 由 {@link com.chua.common.support.spi.ServiceProvider} 按名称分发。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Protocol {

    /**
     * 根据配置创建协议服务器实例。
     *
     * @param setting 协议配置
     * @return 可启动的协议服务器
     */
    ProtocolServer createServer(ProtocolSetting setting);
}
