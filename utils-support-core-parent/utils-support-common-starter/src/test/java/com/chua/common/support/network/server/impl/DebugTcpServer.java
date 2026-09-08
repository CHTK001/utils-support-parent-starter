package com.chua.common.support.network.server.impl;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.filter.UrlMappingServerFilter;
import com.chua.common.support.network.server.handler.ServerHandler;

/**
 * 直接测试 TcpServerRequest 解析和 TcpServerResponse 构建，不启动服务器。
 */
public class DebugTcpServer {
    public static void main(String[] args) throws Exception {
        // Test 1: 解析请求
        String http = "GET /unknown HTTP/1.1\r\nHost: localhost\r\n\r\n";
        TcpServerRequest req = new TcpServerRequest(
                http.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new java.net.InetSocketAddress("127.0.0.1", 54321), null);
        System.out.println("path=" + req.getPath() + " method=" + req.getMethod());

        // Test 2: 手动调用 UrlMappingServerFilter
        ServerSetting setting = ServerSetting.defaults();
        setting.setPort(0);
        setting.setHost("127.0.0.1");
        JdkTcpServer server = new JdkTcpServer(setting);
        server.setHandler(bytes -> bytes);
        server.registerMapping("/hello", (ServerHandler) (r, res) -> res.end("hello"));

        UrlMappingServerFilter filter = server.getUrlMappingFilter();
        System.out.println("filter=null: " + (filter == null));
        System.out.println("routeCount: " + filter.getFactory().routeCount());

        TcpServerResponse resp = new TcpServerResponse();
        ServerHandler handler = filter.getFactory().resolveHandler(req);
        System.out.println("handler for /unknown: " + handler);

        // Simulate filter
        if (handler != null) {
            handler.handle(req, resp);
        } else {
            resp.setStatus(404).setBody("Not Found");
        }
        resp.end();
        byte[] bytes = resp.getReadyBytes();
        String respStr = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("Response: " + respStr.lines().findFirst().orElse(""));

        // Test 3: Check merged reactive filters
        System.out.println("filters: " + server.getFilters().size());
        for (var f : server.getFilters()) {
            System.out.println("  " + f.getClass().getSimpleName() + " protocols=" + java.util.Arrays.toString(f.supportProtocols()));
        }

        server.start();
        System.out.println("after start, routeCount: " + server.getUrlMappingFilter().getFactory().routeCount());
        server.stop();
    }
}
