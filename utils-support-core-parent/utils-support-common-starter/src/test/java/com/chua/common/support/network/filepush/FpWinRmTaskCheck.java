package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 检查远程 FPServer 任务状态（为什么 stat 脚本没产生输出）。
 */
public class FpWinRmTaskCheck {
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
        System.out.println("=== FPServer 任务状态 ===");
        var q = wr.exec().command("schtasks /query /tn FPServer /fo list").execute();
        System.out.println(q.stdout().trim());
        System.out.println("=== stat.ps1 是否存在 ===");
        var e1 = wr.exec().command("cmd.exe /c if exist C:\\fp-push\\stat.ps1 echo EXISTS else echo MISSING").execute();
        System.out.println(e1.stdout().trim());
        System.out.println("=== stat-out.txt 是否存在 ===");
        var e2 = wr.exec().command("cmd.exe /c if exist C:\\fp-push\\stat-out.txt echo EXISTS else echo MISSING").execute();
        System.out.println(e2.stdout().trim());
        wr.close();
        System.exit(0);
    }
}
