import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.AbstractServer;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.aio.AioHttpServer;
import com.chua.common.support.network.server.handler.ServerHandler;
import com.chua.common.support.network.server.nio.NioHttpServer;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * 服务器端 + 进程内 CPU 采样：跑固定时长后打印各线程 CPU 占用，判断事件循环是否已饱和。
 * <p>用法: java HttpServerCpuMain &lt;type&gt; &lt;port&gt; &lt;eventLoops&gt; &lt;duration_s&gt;</p>
 * <p>CPU 采样由外部压测触发：本程序只负责启动服务和计时采样。</p>
 */
public class HttpServerCpuMain {

    private static final ServerHandler ECHO = (req, res) -> {
        res.setBody("hello bench");
        res.end();
    };

    private HttpServerCpuMain() {
    }

    public static void main(String[] args) throws Exception {
        String type = args.length > 0 ? args[0] : "nio";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        int eventLoops = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        int duration = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        ServerSetting setting = ServerSetting.auto();
        setting.setInlineDispatch(true);
        setting.setMaxConcurrency(-1);
        setting.setResponseTimeout(-1);
        setting.setMaxKeepAliveRequests(-1);
        setting.setPort(port);
        if (eventLoops > 0) {
            setting.setEventLoops(eventLoops);
        }

        AbstractServer server = "aio".equals(type) ? new AioHttpServer(setting) : new NioHttpServer(setting);
        server.registerMapping("/echo", HttpMethod.GET, ECHO);
        server.start();
        System.out.println("[server] READY type=" + type + " port=" + server.getPort()
                + " eventLoops=" + setting.getEventLoops());
        System.out.flush();

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        boolean cpuSupported = mx.isThreadCpuTimeSupported();
        if (cpuSupported && !mx.isThreadCpuTimeEnabled()) {
            mx.setThreadCpuTimeEnabled(true);
        }

        // 采样窗口：等压测客户端连上后统计
        long startNs = System.nanoTime();
        long wall = 0;
        while (wall < (long) duration * 1_000_000_000L) {
            Thread.sleep(1000);
            wall = System.nanoTime() - startNs;
        }

        long totalCpu = 0;
        StringBuilder sb = new StringBuilder();
        for (ThreadInfo ti : mx.getThreadInfo(mx.getAllThreadIds())) {
            if (ti == null) {
                continue;
            }
            String name = ti.getThreadName();
            long cpu = mx.getThreadCpuTime(ti.getThreadId());
            if (cpu <= 0) {
                continue;
            }
            totalCpu += cpu;
            if (name.contains("event-loop") || name.contains("nio") || name.contains("worker")
                    || name.contains("Virtual") || name.contains("Accept")) {
                sb.append(String.format("  %-40s %8.2f%%%n", name, cpu * 100.0 / wall));
            }
        }
        System.out.println("[cpu] wall=" + wall / 1_000_000_000L + "s processCpu="
                + String.format("%.2f", totalCpu * 100.0 / wall) + "% (of 1600% machine capacity)");
        System.out.println("[cpu] per thread:");
        System.out.print(sb);
    }
}
