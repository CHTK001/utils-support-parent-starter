package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 在远程 9999 端口启动 EchoServer（纯 TCP 裸速测试用）。
 */
public class FpWinRmStartEcho {
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

        // 杀所有 java（先杀 echo 和 FPServer，统一重启）
        wr.exec().command("cmd.exe /c taskkill /f /im java.exe /t 2>nul").execute();
        Thread.sleep(1000);

        // 重启 FPServer（9777 → D:\fp-sync）
        wr.exec().command("cmd.exe /c schtasks /run /tn FPServer").execute();
        Thread.sleep(2000);
        System.out.println("[2] FPServer restarted");

        // 启动 EchoServer（9999）
        var r = wr.exec().command("cmd.exe /c start \"\" /b C:\\jdk\\jdk21.0.12_8\\bin\\java.exe -cp C:\\fp-push EchoServer 9999 > C:\\fp-push\\echo.log 2>&1").execute();
        System.out.println("[3] echo start: exit=" + r.exitCode() + " out=[" + r.stdout().trim() + "]");
        Thread.sleep(2000);

        // 验证 9999
        var check = wr.exec().command("cmd.exe /c netstat -ano | findstr :9999").execute();
        System.out.println("[4] port 9999: " + check.stdout().trim());

        wr.close();
        System.exit(0);
    }
}
