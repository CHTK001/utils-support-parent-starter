import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 连接数门限验证：把服务端 maxConnections 设为 N，再开 2N 个 TCP 连接，
 * 检查恰好 N 个连接能完成请求(返回 200)，其余被拒(EOF / 连不上)。
 * <p>用法: java MaxConnProbe &lt;port&gt; &lt;maxConnections&gt;</p>
 * <p>返回码: 0=符合预期, 1=不符合。</p>
 */
public class MaxConnProbe {

    private static final String REQ =
            "GET /echo HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";

    private MaxConnProbe() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int max = Integer.parseInt(args[1]);

        List<Socket> held = new ArrayList<>();
        List<Socket> extra = new ArrayList<>();
        int ok = 0;
        int rejected = 0;

        // 先占满名额：逐个建立连接并确认能收到响应
        for (int i = 0; i < max; i++) {
            Socket s = connect(port);
            if (s == null) {
                System.out.println("[probe] FAIL: 第 " + (i + 1) + " 个连接(限额内)被拒"); // [P3C E1 豁免]
                return0(1);
                return;
            }
            if (serve(s)) {
                ok++;
                // 保持连接不关：让名额一直占用
                held.add(s);
            } else {
                System.out.println("[probe] FAIL: 第 " + (i + 1) + " 个连接(限额内)未获响应"); // [P3C E1 豁免]
                return0(1);
                return;
            }
        }

        // 超出名额的连接应被拒：要么建连失败，要么建连成功但立即被关闭
        for (int i = 0; i < max; i++) {
            Socket s = connect(port);
            if (s == null) {
                rejected++;
                continue;
            }
            if (serve(s)) {
                // 竟然被服务了 -> 门限没生效
                extra.add(s);
            } else {
                rejected++;
            }
        }

        int served = ok + extra.size();
        System.out.println("[probe] max=" + max + " kept=" + ok // [P3C E1 豁免]
                + " extra_served=" + extra.size() + " extra_rejected=" + rejected
                + " total_served=" + served);

        for (Socket s : held) {
            closeQuietly(s);
        }
        for (Socket s : extra) {
            closeQuietly(s);
        }

        boolean pass = (extra.size() == 0) && (rejected == max);
        System.out.println(pass ? "[probe] PASS" : "[probe] FAIL"); // [P3C E1 豁免]
        return0(pass ? 0 : 1);
    }

    private static void return0(int code) {
        // 显式 System.exit 以固定返回码(避免 shutdown hook 干扰)
        System.exit(code);
    }

    private static Socket connect(int port) {
        try {
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(5000);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean serve(Socket s) {
        try {
            OutputStream out = s.getOutputStream();
            out.write(REQ.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n <= 0) {
                return false;
            }
            return new String(buf, 0, n, java.nio.charset.StandardCharsets.US_ASCII)
                    .startsWith("HTTP/1.1 200");
        } catch (Exception e) {
            return false;
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
