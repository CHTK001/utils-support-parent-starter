// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.tcp;

import com.chua.common.support.lang.algorithm.crypto.StreamCodec;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 JDK NIO SocketChannel 的 TCP 长度帧客户端实现。
 *
 * <p>每个实例维护独立的虚拟线程池,每次 {@link #call} 通过池提交连接任务,
 * 一个连接一个虚拟线程,线程用完即回收,无连接池管理开销。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("jdk-tcp")
public class JdkTcpClient implements TcpClient {

    private static final int HEADER_SIZE = 4;
    private static final int MAX_BODY_SIZE = 8 * 1024 * 1024;
    private static final int DEFAULT_CONNECT_TIMEOUT = 5000;
    private static final int DEFAULT_READ_TIMEOUT = 10000;

    private final int connectTimeout;
    private final int readTimeout;
// [P3C C1 豁免] 场景化创建线程，需文档化
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 传输加密（null 表示明文，走原生的通道级零拷贝快路径）
     */
    private volatile StreamCodec streamCodec;

    private static final ThreadLocal<ByteBuffer> WRITE_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(1024));
    private static final ThreadLocal<ByteBuffer> HEADER_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(HEADER_SIZE));

    /**
     * 构造方法，创建 JdkTcp客户端 实例。
     */
    public JdkTcpClient() {
        this(0, 0, 0);
    }

    /**
     * 构造方法，创建 JdkTcp客户端 实例。
     *
     * @param poolSize pool大小，不允许为 null
     * @param connectTimeout connect超时时间，不允许为 null
     * @param readTimeout 读取超时时间，不允许为 null
     */
    public JdkTcpClient(int poolSize, int connectTimeout, int readTimeout) {
        this.connectTimeout = connectTimeout > 0 ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
        this.readTimeout = readTimeout > 0 ? readTimeout : DEFAULT_READ_TIMEOUT;
    }

    /**
     * 开启传输加密，与服务端 {@code ServerSetting#setEncryptType/ setEncryptKey} 对应。
     *
     * <p>每次 {@link #call} 都是新连接，因此实现里「按连接握手」的算法（{@code keylog}）
     * 每次调用各握手一次；{@code aes-gcm} 无握手，只做帧式加解密。</p>
     *
     * @param type 加密类型（{@code aes-gcm} / {@code keylog}），为空时默认 {@code aes-gcm}
     * @param key  密钥参数（{@code aes-gcm}：共享口令；{@code keylog}：{@code client:<公钥hex>[#指纹]}）
     * @return 当前实例，支持链式调用
     * @throws IllegalArgumentException 类型不支持或参数缺失
     */
    public JdkTcpClient encrypt(String type, String key) {
        this.streamCodec = StreamCodec.buildStream(type, key);
        return this;
    }

    @Override
    public byte[] call(String host, int port, byte[] request) throws Exception {
        // 直接在本线程(已由调用方虚拟线程池管理)执行连接/交换/关闭,
        // 实例 executor 仅用于生命周期管理,不包装 call 避免额外异步开销
        SocketChannel ch = null;
        try {
            ch = SocketChannel.open();
            ch.configureBlocking(true);
            ch.socket().setReuseAddress(true);
            ch.socket().setTcpNoDelay(true);
            ch.connect(new InetSocketAddress(host, port));
            ch.socket().setSoTimeout(readTimeout);
            StreamCodec codec = this.streamCodec;
            if (codec == null) {
                return exchange(ch, request);
            }
            // 加密路径：帧式加解密只能走流 API，通道仍保持阻塞语义，
            // soTimeout 由 socket 生效（readInt/readFully 超时抛 SocketTimeoutException）
            InputStream rawIn = ch.socket().getInputStream();
            OutputStream rawOut = ch.socket().getOutputStream();
            StreamCodec.Streams streams = codec.open(rawIn, rawOut, true);
            return exchange(streams.in(), streams.out(), request);
        } finally {
            WRITE_BUFFER.remove();
            HEADER_BUFFER.remove();
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException e) {
                    log.debug("关闭 TCP 客户端通道失败，已忽略: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * exchange。
     *
     * @param ch 方法入参 ch
     * @param request 请求，不允许为 null
     * @return 结果值
     * @throws IOException 当执行过程不满足前置条件时
     */
    private byte[] exchange(SocketChannel ch, byte[] request) throws IOException {
        ByteBuffer buf = WRITE_BUFFER.get();
        int needed = HEADER_SIZE + request.length;
        if (buf.capacity() < needed) {
            buf = ByteBuffer.allocate(Math.max(needed, needed * 2));
            WRITE_BUFFER.set(buf);
        } else {
            buf.clear();
        }
        buf.putInt(request.length);
        buf.put(request);
        buf.flip();
        ch.write(buf);
        ByteBuffer headerBuf = HEADER_BUFFER.get();
        headerBuf.clear();
        readFully(ch, headerBuf);
        headerBuf.flip();
        int bodyLen = headerBuf.getInt();
        if (bodyLen <= 0 || bodyLen > MAX_BODY_SIZE) {
            throw new IOException("TCP response too large: " + bodyLen);
        }
        ByteBuffer bodyBuf = ByteBuffer.allocate(bodyLen);
        readFully(ch, bodyBuf);
        return bodyBuf.array();
    }

    /**
     * 在已加密（或解密）的流上做一次长度帧往返。
     *
     * <p>帧格式与明文路径完全一致：{@code [4B 大端长度][消息体]}，
     * 加密只是把它整体套进 {@link StreamCodec} 的帧流里，因此服务端两种模式
     * 对「帧」的语义保持同一套。</p>
     *
     * @param in      解密输入流
     * @param out     加密输出流
     * @param request 请求帧
     * @return 响应帧
     * @throws IOException 读写失败
     */
    private byte[] exchange(InputStream in, OutputStream out, byte[] request) throws IOException {
        DataOutputStream dataOut = new DataOutputStream(out);
        dataOut.writeInt(request.length);
        dataOut.write(request);
        dataOut.flush();
        DataInputStream dataIn = new DataInputStream(in);
        int bodyLen = dataIn.readInt();
        if (bodyLen <= 0 || bodyLen > MAX_BODY_SIZE) {
            throw new IOException("TCP response too large: " + bodyLen);
        }
        byte[] body = new byte[bodyLen];
        dataIn.readFully(body);
        return body;
    }

    /**
     * 读取Fully。
     *
     * @param ch 方法入参 ch
     * @param buf 方法入参 buf
     * @throws IOException 当执行过程不满足前置条件时
     */
    private void readFully(SocketChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int read = ch.read(buf);
            if (read == -1) {
                throw new IOException("TCP connection closed by server");
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }}