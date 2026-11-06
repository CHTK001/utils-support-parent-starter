import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 连接规模探针：建立 N 条 keep-alive 连接并保持，用于测量「每连接占用」。
 *
 * <p>两种模式：</p>
 * <ul>
 *   <li>{@code cold}：只建连、不发请求，测「从未收过数据」的空闲连接成本。</li>
 *   <li>{@code warm}：每条连接先完成一次请求/响应，再保持空闲，测「服务过请求后回到空闲」
 *       的连接成本——这才是长连接服务在线上的常态，也是读缓冲等惰性分配是否真的省内存的检验点。</li>
 * </ul>
 *
 * <p>连接全部建立好（warm 模式还要全部完成一次往返）后打印 {@code cs_stage=HOLDING}，
 * 外层脚本据此采样服务端 RSS，采样完探针才进入 {@code holdSeconds} 的保持期。</p>
 *
 * <p>用法: {@code java ConnScaleProbe <port> <N> <holdSeconds> <cold|warm>}</p>
 */
public class ConnScaleProbe {

    /**
     * 保持会话的请求（keep-alive 而非 close，连接必须常驻）。
     */
    private static final String REQ =
            "GET /echo HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n";

    /**
     * 工具类，不实例化。
     */
    private ConnScaleProbe() {
    }

    /**
     * 入口。
     *
     * @param args 端口、连接数、保持秒数、模式
     * @throws Exception 建连或读写失败
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int target = Integer.parseInt(args[1]);
        int holdSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        String mode = args.length > 3 ? args[3] : "warm";

        List<Socket> conns = new ArrayList<>(target);
        long t0 = System.nanoTime();
        int connectFail = 0;
        for (int i = 0; i < target; i++) {
            Socket s = connect(port);
            if (s == null) {
                connectFail++;
                continue;
            }
            conns.add(s);
        }
        long connectMs = (System.nanoTime() - t0) / 1_000_000L;

        int warmed = 0;
        long warmMs = 0;
        if ("warm".equals(mode)) {
            long t1 = System.nanoTime();
            for (Socket s : conns) {
                if (serve(s)) {
                    warmed++;
                }
            }
            warmMs = (System.nanoTime() - t1) / 1_000_000L;
        }

        System.out.println("cs_mode=" + mode);
        System.out.println("cs_target=" + target);
        System.out.println("cs_connected=" + conns.size());
        System.out.println("cs_connect_fail=" + connectFail);
        System.out.println("cs_warmed=" + warmed);
        System.out.println("cs_connect_ms=" + connectMs);
        System.out.println("cs_warm_ms=" + warmMs);
        System.out.println("cs_stage=HOLDING");
        System.out.flush(); // [P3C E1 豁免]

        Thread.sleep(holdSeconds * 1000L);

        // 保持期结束后逐条确认连接仍可用（服务端没有因为背压/超时把它们拆掉）
        int alive = 0;
        for (Socket s : conns) {
            if (serve(s)) {
                alive++;
            }
        }
        System.out.println("cs_alive_after_hold=" + alive);
        System.out.println("cs_ok=" + (alive == target));
        for (Socket s : conns) {
            closeQuietly(s);
        }
    }

    /**
     * 建立一条 keep-alive 连接。
     *
     * @param port 服务端口
     * @return 连接，失败返回 null
     */
    private static Socket connect(int port) {
        try {
            Socket s = new Socket();
            s.setReceiveBufferSize(4096);
            s.connect(new InetSocketAddress("127.0.0.1", port), 10000);
            s.setSoTimeout(10000);
            s.setTcpNoDelay(true);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在连接上完成一次完整往返（请求 + 读响应）。
     *
     * @param s 连接
     * @return 是否拿到 200
     */
    private static boolean serve(Socket s) {
        try {
            OutputStream out = s.getOutputStream();
            out.write(REQ.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            if (n <= 0) {
                return false;
            }
            return new String(buf, 0, n, StandardCharsets.US_ASCII).startsWith("HTTP/1.1 200");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安静关闭。
     * <p>用 {@code SO_LINGER=0} 让关闭走 RST 而不是 FIN：压测端每次要开上万条连接，
     * 正常关闭会在客户端留下同数量的 TIME_WAIT，两分钟内的第二次运行就会撞上
     * Windows 的 16384 个动态端口上限（表现为「只能连上 3~4 千条，服务端也没死」）。</p>
     *
     * @param s 连接
     */
    private static void closeQuietly(Socket s) {
        try {
            s.setSoLinger(true, 0);
        } catch (Exception e) {
            // ignore
        }
        try {
            s.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
