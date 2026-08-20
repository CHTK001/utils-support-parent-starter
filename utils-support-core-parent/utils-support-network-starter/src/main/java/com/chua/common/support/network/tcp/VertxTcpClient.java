package com.chua.common.support.network.tcp;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Vert.x NetClient 的短连接 TcpClient 实现。
 *
 * <p>{@link #call(String, int, byte[])}：connect → send 请求帧 → 等待响应帧 → close，
 * 一请求一响应一断（短连接）。用于 scatter 等需要短连接的场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VertxTcpClient implements TcpClient {

    private Vertx vertx;
    private final NetClient netClient;

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
            // 收响应帧（第一段数据即完整响应帧）
            socket.handler(buffer -> {
                if (done.compareAndSet(false, true)) {
                    future.complete(buffer.getBytes());
                    socket.close();
                }
            }).exceptionHandler(err -> {
                if (done.compareAndSet(false, true)) {
                    future.completeExceptionally(err);
                }
            });
            socket.write(Buffer.buffer(request));
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
