package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 强杀占用 9777 的旧进程 + 重启 FPServer + 等端口就绪。
 */
public class FpWinRmForceRestart {
    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("[1] connected");

        // 杀掉所有 java（包括占用 9777 的）
        var kill = wr.exec().command("cmd.exe /c taskkill /f /im java.exe /t").execute();
        System.out.println("[2] kill: " + kill.stdout().trim() + " | " + kill.stderr().trim());
        Thread.sleep(2000);

        // 确认 9777 空闲
        var check = wr.exec().command("cmd.exe /c netstat -ano | findstr :9777").execute();
        System.out.println("[3] port 9777 after kill: [" + check.stdout().trim() + "]");

        // 重启 FPServer
        wr.exec().command("schtasks /run /tn FPServer").execute();
        System.out.println("[4] FPServer started");
        Thread.sleep(4000);

        // 验证
        var port2 = wr.exec().command("cmd.exe /c netstat -ano | findstr :9777").execute();
        System.out.println("[5] port 9777 now: " + port2.stdout().trim());
        var log = wr.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("[6] log: " + log.stdout().trim());
        wr.close();
        System.exit(0);
    }
}
