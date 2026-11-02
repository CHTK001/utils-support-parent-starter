import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioHttpServer;

/**
 * 独立进程的服务器端（供跨进程基准使用）：只启动服务并常驻，压测客户端在另一个 JVM 中运行，
 * 避免客户端压测线程与服务端事件循环争抢同一 JVM 的 CPU / GC。
 * <p>用法: java HttpServerMain &lt;nio|aio&gt; &lt;port&gt;</p>
 */
public class HttpServerMain {

    /** echo handler: 返回 hello bench (12 字节) */
    private static final ServerHandler ECHO = (req, res) -> {
        res.setBody("hello bench");
        res.end();
    };

    private HttpServerMain() {
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
        server.registerMapping("/echo", HttpMethod.GET, ECHO);
        server.start();
        System.out.println("[server] READY type=" + type + " port=" + server.getPort()
                + " eventLoops=" + setting.getEventLoops());
        System.out.flush();

        // 常驻：等待外部终止
        Thread.sleep(Long.MAX_VALUE);
    }
}
