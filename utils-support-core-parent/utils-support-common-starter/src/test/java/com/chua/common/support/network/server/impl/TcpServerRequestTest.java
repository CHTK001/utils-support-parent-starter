package com.chua.common.support.network.server.impl;

/**
* tcp服务端请求 单元测试（无 junit 依赖，直接运行 main）。
*
* @author CH
* @since 4.0.0.42
 */
public class TcpServerRequestTest {

    private static final java.net.InetSocketAddress REMOTE =
            new java.net.InetSocketAddress("127.0.0.1", 54321);

    /**
    * main。
    * @param args 参数
     */
    public static void main(String[] args) {
        int passed = 0, total = 0;

 // 测试 1: 获取 /hello
        total++;
        String http1 = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n";
        TcpServerRequest req1 = new TcpServerRequest(
                http1.getBytes(java.nio.charset.StandardCharsets.UTF_8), REMOTE, null);
        boolean ok1 = "GET".equals(req1.getMethod().name())
                && "/hello".equals(req1.getPath())
                && "localhost".equals(req1.getHeader("Host"))
                && req1.getBody().length == 0;
        System.out.println("[parseGetHello] " + (ok1 ? "PASS" : "FAIL")
                + " | path=" + req1.getPath()
                + " method=" + req1.getMethod()
                + " bodyLen=" + req1.getBody().length);
        if (ok1) {
            passed++;
        }

 // 测试 2: POST /echo with 主体
        total++;
        String body = "hello tcp";
        String http2 = "POST /echo HTTP/1.1\r\nContent-Type: text/plain\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n" + body;
        TcpServerRequest req2 = new TcpServerRequest(
                http2.getBytes(java.nio.charset.StandardCharsets.UTF_8), REMOTE, null);
        boolean ok2 = "POST".equals(req2.getMethod().name())
                && "/echo".equals(req2.getPath())
                && body.equals(req2.getBodyString());
        System.out.println("[parsePostEcho] " + (ok2 ? "PASS" : "FAIL")
                + " | path=" + req2.getPath()
                + " body=" + req2.getBodyString());
        if (ok2) {
            passed++;
        }

 // 测试 3: non-HTTP raw 数据
        total++;
        byte[] raw = "raw-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        TcpServerRequest req3 = new TcpServerRequest(raw, REMOTE, null);
        boolean ok3 = "POST".equals(req3.getMethod().name())
                && "/".equals(req3.getPath())
                && "raw-data".equals(req3.getBodyString());
        System.out.println("[parseNonHttp] " + (ok3 ? "PASS" : "FAIL")
                + " | path=" + req3.getPath()
                + " method=" + req3.getMethod()
                + " body=" + req3.getBodyString());
        if (ok3) {
            passed++;
        }

 // 测试 4: 响应 构建
        total++;
        TcpServerResponse resp = new TcpServerResponse();
        resp.setStatus(200).setBody("hello tcp").end();
        byte[] respBytes = resp.getReadyBytes();
        String respStr = new String(respBytes, java.nio.charset.StandardCharsets.UTF_8);
        boolean ok4 = respStr.contains("HTTP/1.1 200") && respStr.contains("hello tcp");
        System.out.println("[buildResponse] " + (ok4 ? "PASS" : "FAIL")
                + " | response: " + respStr.lines().findFirst().orElse(""));
        if (ok4) {
            passed++;
        }

        System.out.println("Result: " + passed + "/" + total + " passed");
        System.exit(passed == total ? 0 : 1);
    }
}
