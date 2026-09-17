package com.chua.common.support.scatter.node;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.proxy.AbstractProxyServer;
import com.chua.common.support.scatter.protocol.ScatterFrame;
import com.chua.common.support.scatter.protocol.ScatterProtocol;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * scatter TCP 节点服务端（短连接，基于 {@link AbstractProxyServer} 骨架）。
 *
 * <p>复用 AbstractProxyServer 的非阻塞批量 accept + Semaphore 连接限流 + 虚拟线程池；
 * {@link #handleConnection(Socket)} 内完成"读帧 → 分派处理 → 回响应帧 → 关闭连接"，
 * 一请求一响应一断，消除长连接 N×(N-1) 连接数爆炸。</p>
 *
 * <p>帧处理委托给 {@link ScatterNodeHandler}（discovery 实现），服务端不感知业务。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
@Slf4j
public class ScatterTcpNodeServer extends AbstractProxyServer {

    private final ScatterNodeHandler handler;

    public ScatterTcpNodeServer(ServerSetting setting, ScatterNodeHandler handler) {
        super(setting);
        this.handler = handler;
    }

    @Override
    protected void handleConnection(Socket clientSocket) {
        try (clientSocket) {
            clientSocket.setSoTimeout((int) Math.max(1000, setting.getReadTimeout()));
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();
            // 读一帧（短连接：单请求-单响应-关闭）
            ScatterFrame frame = readFrame(in);
            if (frame == null) {
                return;
            }
            byte[] response;
            try {
                response = handler.handle(frame);
            } catch (Exception e) {
                log.debug("scatter 帧处理异常: {}", e.getMessage());
                response = new ScatterFrame(ScatterProtocol.TYPE_ACK, frame.getRequestId(),
                        frame.getPath(), new byte[0]).encode();
            }
            // 写 4 字节长度头 + 响应帧（与 JdkTcpClient.exchange 长度帧协议对称）
            byte[] len = new byte[4];
            len[0] = (byte) (response.length >>> 24);
            len[1] = (byte) (response.length >>> 16);
            len[2] = (byte) (response.length >>> 8);
            len[3] = (byte) response.length;
            out.write(len);
            out.write(response);
            out.flush();
        } catch (IOException e) {
            log.debug("scatter 连接处理异常: {}", e.getMessage());
        } finally {
            activeConnections.decrementAndGet();
        }
    }

    @Override
    public com.chua.common.support.network.ProtocolType getProtocolType() {
        return com.chua.common.support.network.ProtocolType.TCP;
    }

    /**
    * 从输入流读取一帧（兼容 TcpClient 长度帧协议：4 字节长度头 + ScatterFrame body）。
    *
    * @param in 输入流
    * @return 帧，EOF 返回 null
    */
    private ScatterFrame readFrame(InputStream in) throws IOException {
        byte[] lenBytes = new byte[4];
        int n = readFully(in, lenBytes);
        if (n == -1) {
            return null;
        }
        if (n < 4) {
            throw new IOException("帧长度头不完整: " + n);
        }
        int bodyLen = ((lenBytes[0] & 0xff) << 24) | ((lenBytes[1] & 0xff) << 16)
                | ((lenBytes[2] & 0xff) << 8) | (lenBytes[3] & 0xff);
        if (bodyLen <= 0 || bodyLen > 16 * 1024 * 1024) {
            throw new IOException("帧长度越界: " + bodyLen);
        }
        byte[] body = new byte[bodyLen];
        readFully(in, body);
        return ScatterFrame.decode(body);
    }

    /**
    * 读取完整字节块。
    *
    * @param in   输入流
    * @param buf  目标缓冲
    * @return 已读字节数；首字节即 EOF 返回 -1
    */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r == -1) {
                return total == 0 ? -1 : total;
            }
            total += r;
        }
        return total;
    }
}
