package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 把本地失败的 3 个文件补推到远程 D:\fp-sync，然后远程校验统计。
 */
public class FpWinRmFixVerify {
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
        System.out.println("=== connected ===");

        // 1. 写统计脚本到远程
        wr.exec().command("cmd.exe /c del C:\\fp-push\\count.ps1 2>nul").execute();
        wr.exec().command("cmd.exe /c echo (Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum) | "
                + "ForEach-Object { \"count=$($_.Count) bytes=$([math]::Round($_.Sum/1GB,2))GB\" } > C:\\fp-push\\count.ps1").execute();
        System.out.println("[1] count.ps1 written");

        // 2. 执行统计
        var r1 = wr.exec().command("powershell -ExecutionPolicy Bypass -File C:\\fp-push\\count.ps1").execute();
        System.out.println("[2] D:\\fp-sync 统计: " + r1.stdout().trim());

        wr.close();
        System.exit(0);
    }
}
