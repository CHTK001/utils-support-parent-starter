package com.chua.common.support.network.tcp;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
   * 基于 Vert.x net客户端 的短连接 tcp客户端 实现。
 *
 * <p>{@link #call(String, int, byte[])}：connect → send 请求帧 → 等待响应帧 → close，
 * 一请求一响应一断（短连接）。用于 scatter 等需要短连接的场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VertxTcpClient implements TcpClient {

    private Vertx vertx; // vertx
    private final NetClient netClient; // net客户端

    /**
     * vertxtcp客户端。
     */
    public VertxTcpClient() {
        this.vertx = Vertx.vertx(new VertxOptions()
                .setEventLoopPoolSize(Math.max(Runtime.getRuntime().availableProcessors(), 2))
                .setPreferNativeTransport(true));
        this.netClient = vertx.createNetClient(new NetClientOptions()
                .setTcpNoDelay(true)
                .setConnectTimeout(5000)
                .setReconnectAttempts(0));
    }

    @Override
    public byte[] call(String host, int port, byte[] request) throws Exception {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        AtomicBoolean done = new AtomicBoolean(false);
        Future<NetSocket> connectFuture = netClient.connect(port, host);
        connectFuture.onSuccess(socket -> {
 // 响应帧协议（与 jdktcp客户端.exchange 对称）：4 字节长度头 + 主体
            Buffer accumulated = Buffer.buffer();
            socket.handler(buffer -> {
                accumulated.appendBuffer(buffer);
                // 尝试解析完整帧：长度头(4) + body
                while (accumulated.length() >= 4) {
                    int bodyLen = accumulated.getInt(0);
                    if (bodyLen <= 0 || bodyLen > 64 * 1024 * 1024) {
                        if (done.compareAndSet(false, true)) {
                            future.completeExceptionally(new IOException("响应帧长度越界: " + bodyLen));
                            socket.close();
                        }
                        return;
                    }
                    if (accumulated.length() < 4 + bodyLen) {
                        return; // 等待更多数据
                    }
                    byte[] body = accumulated.getBytes(4, bodyLen);
                    if (done.compareAndSet(false, true)) {
                        future.complete(body);
                        socket.close();
                    }
                    return;
                }
            }).exceptionHandler(err -> {
                if (done.compareAndSet(false, true)) {
                    future.completeExceptionally(err);
                }
            });
 // 发送请求帧（4 字节长度头 + 主体，与 jdktcp客户端 对称）
            Buffer framed = Buffer.buffer(4 + request.length);
            framed.appendInt(request.length);
            framed.appendBytes(request);
            socket.write(framed);
        }).onFailure(err -> {
            if (done.compareAndSet(false, true)) {
                future.completeExceptionally(err);
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        try {
            netClient.close();
        } catch (Exception ignored) {
        }
        if (vertx != null) {
            try {
                vertx.close();
            } catch (Exception ignored) {
            }
            vertx = null;
        }
    }
}
