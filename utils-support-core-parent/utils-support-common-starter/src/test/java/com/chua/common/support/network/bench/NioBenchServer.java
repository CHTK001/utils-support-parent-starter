// [P3C A20 豁免] A20/A30/A31: 压测服务端与协议常量含魔法值场景，豁免。
// [P3C E1 豁免] [P3C SE-安全 豁免] CLI 压测入口，标准输出与退出码用于脚本判定。
package com.chua.common.support.network.bench;

import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.nio.NioHttpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 独立进程的 NIO HTTP 压测服务端：把指定文件的内容原样回在 {@code /bench}。
 *
 * <p>用途是与 nginx 做同 payload 对比：nginx 从磁盘读什么文件，这里就读同一个文件，
 * 这样响应体大小、内容完全一致，差异只来自服务实现。</p>
 *
 * <p>用法：{@code java -cp <cp> NioBenchServer <port> <file>}</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public final class NioBenchServer {

    /**
     * 工具类不允许实例化。
     */
    private NioBenchServer() {
    }

    /**
     * 入口。
     *
     * @param args port file
     * @throws Exception 启动失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("USAGE: <port> <file>");
            System.exit(2);
            return;
        }
        int port = Integer.parseInt(args[0]);
        String body = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);

        ServerSetting setting = new ServerSetting();
        setting.setHost("127.0.0.1");
        setting.setPort(port);
        setting.setAuto(false);

        NioHttpServer server = new NioHttpServer(setting);
        server.registerMapping("/bench", HttpMethod.GET, (request, response) -> {
            response.setStatus(200).setContentType("text/html").setBody(body);
            response.end();
        });
        server.start();
        System.out.println("READY port=" + server.getPort() + " bytes=" + body.length());
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
