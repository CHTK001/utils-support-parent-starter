import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioHttpServer;

/**
 * 功能验证服务：覆盖响应头快/慢路径、自定义头、状态码、二进制体、keep-alive。
 * <p>用法: java FnTestMain &lt;nio|aio&gt; &lt;port&gt; [maxConnections] [sseEvents]</p>
 */
public class FnTestMain {

    private FnTestMain() {
    }

    public static void main(String[] args) throws Exception {
        String type = args.length > 0 ? args[0] : "nio";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        // 可选:限制最大连接数,用于验证连接数门限(不传则走 autoConfig 的堆推导值)
        int maxConnections = args.length > 2 ? Integer.parseInt(args[2]) : -1;

        ServerSetting setting = ServerSetting.auto();
        setting.setInlineDispatch(true);
        setting.setMaxConcurrency(-1);
        setting.setResponseTimeout(-1);
        setting.setMaxKeepAliveRequests(-1);
        setting.setPort(port);
        if (maxConnections > 0) {
            setting.setMaxConnections(maxConnections);
        }

        AbstractServer server = "aio".equals(type) ? new AioHttpServer(setting) : new NioHttpServer(setting);

        // 1) 快路径: 无自定义头 + 200
        server.registerMapping("/echo", HttpMethod.GET, (req, res) -> {
            res.setBody("hello bench");
            res.end();
        });
        // 2) 慢路径: 自定义多个头 + 200
        server.registerMapping("/headers", HttpMethod.GET, (req, res) -> {
            res.setHeader("X-Custom-A", "alpha");
            res.setHeader("X-Custom-B", "beta");
            res.setHeader("Cache-Control", "no-store");
            res.setBody("with headers");
            res.end();
        });
        // 3) 慢路径: 自定义状态码
        server.registerMapping("/created", HttpMethod.GET, (req, res) -> {
            res.setStatus(201);
            res.setHeader("X-Custom-A", "created");
            res.setBody("made");
            res.end();
        });
        // 4) 覆盖系统头: 显式 Content-Type / Connection
        server.registerMapping("/typed", HttpMethod.GET, (req, res) -> {
            res.setHeader("Content-Type", "application/json");
            res.setHeader("Connection", "keep-alive");
            res.setBody("{\"ok\":true}");
            res.end();
        });
        // 5) 空体
        server.registerMapping("/empty", HttpMethod.GET, (req, res) -> res.end());
        // 6) 大数据体
        server.registerMapping("/big", HttpMethod.GET, (req, res) -> {
            byte[] body = new byte[100000];
            for (int i = 0; i < body.length; i++) {
                body[i] = (byte) ('a' + (i % 26));
            }
            res.setBody(body);
            res.end();
        });
        // 7) SSE 流式推送: 用于验证写队列背压(慢客户端应被服务端断开)
        //    事件数可由 ?n= 覆盖,便于同一进程内分别验证「快速消费者收到全量」与「慢消费者被断开」
        final int sseEvents = args.length > 3 ? Integer.parseInt(args[3]) : 100;
        final String ssePayload = repeat('x', 512);
        server.registerMapping("/sse", HttpMethod.GET, (req, res) -> {
            int total = parseEvents(req.getParam("n"), sseEvents);
            res.sse();
            int sent = 0;
            for (int i = 0; i < total; i++) {
                // 背压断言:消费者被断开后必须立刻停止生成,不能空转到事件数耗尽
                if (res.isChannelClosed()) {
                    break;
                }
                res.sseEvent(null, ssePayload);
                sent++;
            }
            System.out.println("[fn] sse generated=" + sent + " total=" + total); // [P3C E1 豁免]
            res.sseClose();
        });

        server.start();
        System.out.println("[fn] READY type=" + type + " port=" + server.getPort()); // [P3C E1 豁免]
        System.out.flush(); // [P3C E1 豁免]
        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * 解析 ?n= 事件数,非法值回落默认值。
     *
     * @param raw      查询参数原始值
     * @param fallback 默认事件数
     * @return 事件数
     */
    private static int parseEvents(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 构造重复字符的字符串,避免引入额外依赖。
     *
     * @param c     字符
     * @param count 重复次数
     * @return 字符串
     */
    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }
}
