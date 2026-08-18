package com.chua.ionet.support;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.ionet.support.server.IonetServer;
import com.iohao.net.external.core.config.ExternalJoinEnum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ionet 协议分类与 ServerFilter 兼容测试。
 *
 * <p>ionet 底层即标准 TCP / WEBSOCKET / UDP 连接（ExternalJoinEnum），
 * 复用对应协议分类，不单独新增 IONET 协议类型；声明支持底层协议的 filter 应生效。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class IonetProtocolFilterTest {

    /**
     * 触发标记过滤器（声明指定协议）。
     */
    static class FlagFilter implements ServerFilter {
        /**
         * 过滤器是否被调用标记
         */
        final AtomicBoolean invoked = new AtomicBoolean(false);
        /**
         * 经过的请求路径列表
         */
        final List<String> paths = new ArrayList<>();
        /**
         * 声明支持的协议类型
         */
        private final ProtocolType[] protocols;

        FlagFilter(ProtocolType... protocols) {
            this.protocols = protocols;
        }

        @Override
        public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
            invoked.set(true);
            paths.add(request.getPath());
            chain.doFilter(request, response);
        }

        @Override
        public ProtocolType[] supportProtocols() {
            return protocols;
        }
    }

    private static IonetServer build(ExternalJoinEnum joinType) {
        return IonetServer.builder()
                .port(10100)
                .joinType(joinType)
                .scanActionPackage(IonetProtocolFilterTest.class)
                .build();
    }

    @Test
    void protocolMappingReusesUnderlyingJoinType() {
        assertEquals(ProtocolType.TCP, build(ExternalJoinEnum.TCP).getProtocolType(), "TCP joinType 复用 TCP 协议");
        assertEquals(ProtocolType.UDP, build(ExternalJoinEnum.UDP).getProtocolType(), "UDP joinType 复用 UDP 协议");
        assertEquals(ProtocolType.WS, build(ExternalJoinEnum.WEBSOCKET).getProtocolType(), "WEBSOCKET joinType 复用 WS 协议");
        assertEquals(ProtocolType.TCP, build(ExternalJoinEnum.EXT_SOCKET).getProtocolType(), "EXT_SOCKET 回落 TCP 协议");
    }

    @Test
    void protocolStringMapping() {
        assertEquals("tcp", build(ExternalJoinEnum.TCP).getProtocol());
        assertEquals("udp", build(ExternalJoinEnum.UDP).getProtocol());
        assertEquals("ws", build(ExternalJoinEnum.WEBSOCKET).getProtocol());
    }

    @Test
    void udpDeclaredFilterMatchesIonetUdp() {
        // 与 KCP 一致：声明支持 UDP 的 filter 对 ionet(UDP) 同样生效
        FlagFilter filter = new FlagFilter(ProtocolType.UDP);
        assertTrue(filter.supportProtocol(ProtocolType.UDP), "filter 声明 UDP 应匹配 UDP");
        assertTrue(filter.supportProtocol(build(ExternalJoinEnum.UDP).getProtocolType()),
                "ionet(UDP) 复用 UDP 协议，声明 UDP 的 filter 应生效");
    }

    @Test
    void tcpDeclaredFilterMatchesIonetTcp() {
        FlagFilter filter = new FlagFilter(ProtocolType.TCP);
        assertTrue(filter.supportProtocol(build(ExternalJoinEnum.TCP).getProtocolType()),
                "ionet(TCP) 复用 TCP 协议，声明 TCP 的 filter 应生效");
        assertFalse(filter.supportProtocol(build(ExternalJoinEnum.UDP).getProtocolType()),
                "声明 TCP 的 filter 不应匹配 ionet(UDP)");
    }

    @Test
    void serverSpiRegisteredViaBuilderType() {
        // ionet 已接入 common Server 体系：ServerBuilder.type("ionet") 应能创建 IonetServer
        Server server = ServerBuilder.create()
                .type("ionet")
                .host("127.0.0.1")
                .port(10100)
                .build();
        assertTrue(server instanceof IonetServer, "ServerBuilder.type(\"ionet\") 应创建 IonetServer");
        assertEquals(ProtocolType.TCP, server.getProtocolType(), "默认 joinType=TCP 复用 TCP 协议");
    }

    @Test
    void genericFilterMatchesAllIonet() {
        // 未声明协议（空数组）的 filter 匹配所有协议
        FlagFilter filter = new FlagFilter();
        assertTrue(filter.supportProtocol(build(ExternalJoinEnum.TCP).getProtocolType()));
        assertTrue(filter.supportProtocol(build(ExternalJoinEnum.UDP).getProtocolType()));
        assertTrue(filter.supportProtocol(build(ExternalJoinEnum.WEBSOCKET).getProtocolType()));
    }
}
