package com.chua.apollo.support.spi;

import com.chua.datasource.support.middleware.MiddlewareConnectRequest;
import com.chua.datasource.support.middleware.MiddlewareTestResult;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerProbe {

    public static void main(String[] args) throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        AtomicBoolean going = new AtomicBoolean(true);
        Thread t = new Thread(() -> {
            try {
                while (going.get()) {
                    Socket sock = ss.accept();
                    System.out.println("ACCEPTED " + sock);
                    System.out.println("local port " + sock.getLocalPort());
                    BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
                    StringBuilder req = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null && !line.isEmpty()) {
                        req.append(line).append("\n");
                    }
                    System.out.println("REQ: " + req);
                    String head = req.toString().split("\n")[0];
                    String path = head.split(" ")[1];
                    String body = "{\"code\":0,\"data\":[{\"appId\":\"10001\",\"name\":\"DemoApp\"}]}";
                    System.out.println("PATH: " + path + " body len " + body.length());
                    PrintWriter w = new PrintWriter(sock.getOutputStream(), true);
                    w.print("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
                    w.flush();
                    System.out.println("WROTE");
                    sock.close();
                }
            } catch (Exception e) {
                System.out.println("MOCK EXCEPTION: " + e);
            }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(400);
        System.out.println("STARTING MOCK SERVER PROBE on port " + port);
        ApolloClientProvider p = new ApolloClientProvider();
        MiddlewareConnectRequest r = MiddlewareConnectRequest.of("127.0.0.1", port, "", "", "");
        MiddlewareTestResult t2 = p.test(r);
        System.out.println("RESULT: " + t2);
        going.set(false);
    }

    @Test
    void nothing() {
    }
}
