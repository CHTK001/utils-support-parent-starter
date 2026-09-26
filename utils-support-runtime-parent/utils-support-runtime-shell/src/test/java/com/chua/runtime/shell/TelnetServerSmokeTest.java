package com.chua.runtime.shell;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Telnet Shell 暴露面冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.runtime.shell.TelnetServerSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>Shell 端口没有任何鉴权（可执行 runtime start/stop、线程与内存查看、日志 tail），
 * 因此默认必须只绑回环，且并发会话数要有限。全部连接都打本机端口，不涉及外部网络。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TelnetServerSmokeTest {

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 成功计数
     */
    private static int passCount = 0;

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) throws Exception {
        testDefaultBindIsLoopback();
        testSessionLimit();
        testStopReleasesPort();

        System.out.println("========================================");
        System.out.println("TelnetServerSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 默认只绑回环
     */
    private static void testDefaultBindIsLoopback() throws Exception {
        int port = freePort();
        TelnetServer server = new TelnetServer();
        try {
            check(server.getLocalPort() == -1, "未启动时端口读数为 -1");
            server.start(port);
            InetAddress bound = server.getLocalAddress();
            check(bound != null && bound.isLoopbackAddress(),
                    "默认监听地址为回环（实际=" + bound + "）");
            check(server.getLocalPort() == port, "监听端口与入参一致");
            check(server.isRunning(), "isRunning 反映启动状态");
        } finally {
            server.stop();
        }
        check(!server.isRunning(), "stop 后 isRunning 归位");
    }

    /**
     * 超出并发上限的连接被服务端直接关闭
     */
    private static void testSessionLimit() throws Exception {
        int port = freePort();
        TelnetServer server = new TelnetServer().withMaxSessions(1);
        try {
            server.start(port);
            try (Socket held = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
                held.setTcpNoDelay(true);
                try (Socket second = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
                    second.setSoTimeout(1500);
                    boolean closed = false;
                    try {
                        StringBuilder sb = new StringBuilder();
                        int ch;
                        while ((ch = second.getInputStream().read()) != -1 && sb.length() < 40) {
                            sb.append((char) ch);
                        }
                        closed = true;
                    } catch (SocketTimeoutException e) {
                        closed = false;
                    }
                    check(closed, "超限连接被服务端关闭（未被静默保留）");
                }
            }
        } finally {
            server.stop();
        }
    }

    /**
     * 停止后端口可重新绑定
     */
    private static void testStopReleasesPort() throws Exception {
        int port = freePort();
        TelnetServer server = new TelnetServer();
        server.start(port);
        server.stop();
        boolean rebound;
        try (ServerSocket ignored = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))) {
            rebound = true;
        } catch (Exception e) {
            rebound = false;
        }
        check(rebound, "stop 后端口被释放可重绑");
    }

    /**
     * 取一个当前空闲的本机端口
     *
     * @return 端口号
     */
    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        }
    }

    /**
     * 校验并计数
     *
     * @param condition 条件
     * @param message 消息
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }
}
