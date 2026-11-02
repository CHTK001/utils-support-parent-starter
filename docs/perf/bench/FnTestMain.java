import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioHttpServer;

/**
 * 功能验证服务：覆盖响应头快/慢路径、自定义头、状态码、二进制体、keep-alive。
 * <p>用法: java FnTestMain &lt;nio|aio&gt; &lt;port&gt;</p>
 */
public class FnTestMain {

    private FnTestMain() {
    }

    public static void main(String[] args) throws Exception {
        String type = args.length > 0 ? args[0] : "nio";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        ServerSetting setting = ServerSetting.auto();
        setting.setInlineDispatch(true);
        setting.setMaxConcurrency(-1);
        setting.setResponseTimeout(-1);
        setting.setMaxKeepAliveRequests(-1);
        setting.setPort(port);

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

        server.start();
        System.out.println("[fn] READY type=" + type + " port=" + server.getPort());
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
