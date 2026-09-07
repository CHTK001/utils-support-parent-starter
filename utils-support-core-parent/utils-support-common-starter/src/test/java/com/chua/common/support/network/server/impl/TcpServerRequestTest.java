package com.chua.common.support.network.server.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TcpServerRequest 单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class TcpServerRequestTest {

    private static final java.net.InetSocketAddress REMOTE =
            new java.net.InetSocketAddress("127.0.0.1", 54321);

    @Test
    void parseGetHello() {
        String http = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n";
        TcpServerRequest req = new TcpServerRequest(
                http.getBytes(java.nio.charset.StandardCharsets.UTF_8), REMOTE, null);
        assertEquals("GET", req.getMethod().name());
        assertEquals("/hello", req.getPath());
        assertEquals("GET /hello HTTP/1.1", req.getUri());
        assertEquals("localhost", req.getHeader("Host"));
        assertEquals(0, req.getBody().length);
    }

    @Test
    void parsePostEcho() {
        String body = "hello tcp";
        String http = "POST /echo HTTP/1.1\r\nContent-Type: text/plain\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n" + body;
        TcpServerRequest req = new TcpServerRequest(
                http.getBytes(java.nio.charset.StandardCharsets.UTF_8), REMOTE, null);
        assertEquals("POST", req.getMethod().name());
        assertEquals("/echo", req.getPath());
        assertEquals(body, req.getBodyString());
    }

    @Test
    void parseNonHttp() {
        byte[] raw = "raw-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        TcpServerRequest req = new TcpServerRequest(raw, REMOTE, null);
        assertEquals("POST", req.getMethod().name());
        assertEquals("/", req.getPath());
        assertEquals("raw-data", req.getBodyString());
    }
}
