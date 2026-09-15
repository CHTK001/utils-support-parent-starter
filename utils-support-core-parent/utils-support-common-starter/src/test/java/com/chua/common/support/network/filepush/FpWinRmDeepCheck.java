package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

public class FpWinRmDeepCheck {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("=== 当前监听 9777 ===");
        var p1 = wr.exec().command("cmd.exe /c netstat -ano | findstr 9777").execute();
        System.out.println(p1.stdout());

        System.out.println("=== 所有 java 进程及命令行 ===");
        var p2 = wr.exec().command("cmd.exe /c wmic process where \"name='java.exe'\" get ProcessId,CommandLine /format:list").execute();
        System.out.println(p2.stdout());

        System.out.println("=== server.log 修改时间与内容 ===");
        var p3 = wr.exec().command("cmd.exe /c dir /tc C:\\fp-push\\server.log").execute();
        System.out.println(p3.stdout());
        var p4 = wr.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("content=[" + p4.stdout() + "]");

        System.out.println("=== 计划任务上次运行结果 ===");
        var p5 = wr.exec().command("cmd.exe /c schtasks /query /tn FPServer /v /fo list").execute();
        System.out.println(p5.stdout().substring(0, Math.min(800, p5.stdout().length())));

        wr.close();
        System.exit(0);
    }
}