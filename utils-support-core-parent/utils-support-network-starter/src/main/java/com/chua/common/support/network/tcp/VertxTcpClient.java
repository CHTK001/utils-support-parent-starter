// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.tcp;

import com.chua.common.support.lang.algorithm.crypto.StreamCodec;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Vert.x net客户端 的短连接 tcp客户端 实现。
 *
 * <p>{@link #call(String, int, byte[])}：connect → send 请求帧 → 等待响应帧 → close，
 * 一请求一响应一断（短连接）。用于 scatter 等需要短连接的场景。</p>
 *
 * <p>需要传输加密时用 {@link #encrypt(String, String)}，与 {@code JdkTcpClient} 保持同一套
 * {@code StreamCodec} 参数与帧格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VertxTcpClient implements TcpClient {

    /**
     * 连接超时（毫秒），与 {@link NetClientOptions#setConnectTimeout(int)} 保持同一口径。
     */
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    /**
     * 读取超时（毫秒），与明文路径 {@code future.get(10, SECONDS)} 保持同一口径。
     */
    private static final int READ_TIMEOUT_MILLIS = 10000;

    /**
     * 响应帧体上限。
     */
    private static final int MAX_BODY_SIZE = 64 * 1024 * 1024;

    private Vertx vertx; // vertx
    private final NetClient netClient; // net客户端

    /**
     * 传输加密编解码器，为空表示明文。
     */
    private volatile StreamCodec streamCodec;

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

    /**
     * 开启传输加密，与服务端 {@code ServerSetting#setEncryptType/setEncryptKey} 对应。
     *
     * <p>与 {@code JdkTcpClient#encrypt(String, String)} 同口径：{@code aes-gcm} 为预共享口令，
     * {@code keylog} 为每连接握手的会话密钥。加密启用后每次 {@link #call} 走阻塞流路径
     * （{@link StreamCodec} 是 {@code InputStream/OutputStream} API，与 NetSocket 的
     * 回调式 handler 无法混用），帧格式仍是「4 字节大端长度头 + 主体」，与明文路径一致。</p>
     *
     * @param type 加密类型（{@code aes-gcm} / {@code keylog}），为空时默认 {@code aes-gcm}
     * @param key  密钥参数（{@code aes-gcm}：共享口令；{@code keylog}：{@code client:<公钥hex>[#指纹]}）
     * @return 当前实例，支持链式调用
     * @throws IllegalArgumentException 类型不支持或参数缺失
     */
    public VertxTcpClient encrypt(String type, String key) {
        this.streamCodec = StreamCodec.buildStream(type, key);
        return this;
    }

    @Override
    public byte[] call(String host, int port, byte[] request) throws Exception {
        StreamCodec codec = this.streamCodec;
        if (codec != null) {
            return callEncrypted(codec, host, port, request);
        }
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
                    if (bodyLen <= 0 || bodyLen > MAX_BODY_SIZE) {
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

    /**
     * 加密路径：一次请求一连接，用 {@link StreamCodec} 包住原始流后做长度帧往返。
     *
     * <p>阻塞是刻意的——加密流是拉取式 API，Vert.x 的 {@code NetSocket} 只有推送式
     * handler，两者混用需要额外的桥接缓冲，而本客户端本来就是「一请求一响应一断」的短连接，
     * 阻塞开销可以忽略。超时语义与明文路径一致：连接 5s、单次读取 10s。</p>
     *
     * @param codec   编解码器
     * @param host    目标主机
     * @param port    目标端口
     * @param request 请求帧主体
     * @return 响应帧主体
     * @throws IOException 连接、握手或读写失败
     */
    private byte[] callEncrypted(StreamCodec codec, String host, int port, byte[] request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            StreamCodec.Streams streams = codec.open(
                    socket.getInputStream(), socket.getOutputStream(), true);
            DataOutputStream out = new DataOutputStream(streams.out());
            out.writeInt(request.length);
            out.write(request);
            out.flush();

            DataInputStream in = new DataInputStream(streams.in());
            int bodyLen = in.readInt();
            if (bodyLen <= 0 || bodyLen > MAX_BODY_SIZE) {
                throw new IOException("响应帧长度越界: " + bodyLen);
            }
            byte[] body = new byte[bodyLen];
            in.readFully(body);
            return body;
        }
    }

    @Override
    public void close() {
        try {
            netClient.close();
        } catch (Exception e) {
            log.debug("[network] 关闭 net 客户端失败，已忽略: {}", e.getMessage());
        }
        if (vertx != null) {
            try {
                vertx.close();
            } catch (Exception e) {
                log.debug("[network] 关闭 vertx 实例失败，已忽略: {}", e.getMessage());
            }
            vertx = null;
        }
    }
}