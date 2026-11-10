// [P3C A20 豁免] A20/A30/A31: 测试构造的帧字节与断言消息含魔法值场景，豁免。
package com.chua.common.support.network.server.aio;

import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.tcp.callback.TcpServerHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AioTcpServer} 帧模式的长度帧重组验证。
 *
 * <p>{@code TcpServer} 契约要求「4 字节大端长度头 + 消息体」，并且实现类负责
 * <b>读取并拼装完整帧</b>。但 AIO 的读完成语义只是「读到了若干字节」，与帧边界无关。
 * 修复前 {@code issueFrameRead} 把每次读到的全部字节直接交给处理器，于是：</p>
 * <ul>
 *   <li>半包：长度头单独到达时，那 4 个字节被当成一个「请求」；</li>
 *   <li>粘包：两帧合并进一次读时，第二帧被并进第一帧，对端再也等不到它的响应。</li>
 * </ul>
 *
 * <p>下面两个用例分别钉住这两种坏法，全部走真实套接字。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
class AioTcpFrameFramingTest {

    /**
     * 取一个当前空闲的端口。
     *
     * @return 空闲端口
     * @throws Exception 套接字操作失败
     */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 起一个帧模式服务：把收到的帧体原样回显，前面加 {@code echo:}。
     *
     * @param port 端口
     * @return 已启动的服务
     */
    private static AioTcpServer startEchoServer(int port) {
        ServerSetting setting = ServerSetting.builder()
                .protocol("tcp").host("127.0.0.1").port(port).auto(false).build();
        AioTcpServer server = new AioTcpServer(setting);
        server.setFrameHandler(new TcpServerHandler() {
            @Override
            public byte[] handle(byte[] request) {
                byte[] prefix = "echo:".getBytes(StandardCharsets.UTF_8);
                byte[] out = new byte[prefix.length + request.length];
                System.arraycopy(prefix, 0, out, 0, prefix.length);
                System.arraycopy(request, 0, out, prefix.length, request.length);
                return out;
            }
        });
        server.start();
        return server;
    }

    /**
     * 把消息体拼成完整长度帧。
     *
     * @param body 帧体
     * @return 4 字节长度头 + 帧体
     */
    private static byte[] frame(byte[] body) {
        return ByteBuffer.allocate(4 + body.length)
                .putInt(body.length).put(body).array();
    }

    /**
     * 读出一条长度帧的帧体。
     *
     * @param in 输入流
     * @return 帧体字节
     * @throws Exception 读失败
     */
    private static byte[] readFrame(DataInputStream in) throws Exception {
        int len = in.readInt();
        assertTrue(len > 0 && len < 4 * 1024 * 1024, "非法帧长度: " + len);
        byte[] body = new byte[len];
        in.readFully(body);
        return body;
    }

    /**
     * 半包：长度头与消息体分两次发出。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(60)
    @DisplayName("半包（头与体分两次到达）必须拼成完整一帧")
    void splitHeaderAndBodyAssemblesOneFrame() throws Exception {
        int port = freePort();
        AioTcpServer server = startEchoServer(port);
        byte[] body = "hello-aio".getBytes(StandardCharsets.UTF_8);
        byte[] full = frame(body);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15000);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 只发 4 字节长度头
            out.write(full, 0, 4);
            out.flush();
            Thread.sleep(300L);
            // 再发消息体
            out.write(full, 4, full.length - 4);
            out.flush();

            byte[] echoed = readFrame(in);
            String text = new String(echoed, StandardCharsets.UTF_8);
            // 修复前：服务端把先到的 4 字节当成一帧，回的是 echo:+那 4 个长度头字节，
            // 而不是完整消息体的回显
            assertEquals("echo:hello-aio", text,
                    "半包必须拼成完整一帧后再交给处理器");
        } finally {
            server.stop();
        }
    }

    /**
     * 粘包：两帧合并成一次写发出。
     *
     * @throws Exception 套接字操作失败
     */
    @Test
    @Timeout(60)
    @DisplayName("粘包（两帧一次到达）必须逐帧派发、各自有响应")
    void coalescedFramesAreDispatchedSeparately() throws Exception {
        int port = freePort();
        AioTcpServer server = startEchoServer(port);
        byte[] first = frame("one".getBytes(StandardCharsets.UTF_8));
        byte[] second = frame("two".getBytes(StandardCharsets.UTF_8));
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);

        List<String> bodies = new ArrayList<>(2);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15000);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 两帧合并成一次写
            out.write(both);
            out.flush();

            bodies.add(new String(readFrame(in), StandardCharsets.UTF_8));
            bodies.add(new String(readFrame(in), StandardCharsets.UTF_8));
        } finally {
            server.stop();
        }
        // 修复前：合并字节被当成一帧，只会收到一个 echo:onetwo，第二个响应永远等不到
        assertEquals(2, bodies.size());
        assertEquals("echo:one", bodies.get(0));
        assertEquals("echo:two", bodies.get(1));
    }
}
