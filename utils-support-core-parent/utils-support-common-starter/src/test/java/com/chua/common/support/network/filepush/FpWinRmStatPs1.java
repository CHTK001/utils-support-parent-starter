package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 远程执行文件统计：写 PS1 脚本到 D 盘，PowerShell 执行，结果写文本文件供 SMB 读取。
 */
public class FpWinRmStatPs1 {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("=== 执行远程统计 ===");

        // 写 PS1 脚本（PowerShell 用 Out-File 写 PS1）
        String ps1Content = "(Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum)"
                + " | Out-File 'C:\\fp-push\\stat-out.txt'";
        String ps1File = "C:\\fp-push\\stat.ps1";

        // 直接用 PowerShell 写脚本文件（避免 cmd 转义 $ 问题）
        String writeCmd = "powershell -Command \"& { "
                + "'$s = Get-ChildItem D:\\fp-sync -Recurse -File | Measure-Object -Property Length -Sum' | "
                + "Out-File '" + ps1File + "' }\"";
        wr.exec().command(writeCmd).execute();
        System.out.println("[1] stat.ps1 已写入");

        // 执行统计脚本（可能较慢，12.8万文件）
        var exec = wr.exec().command("powershell -ExecutionPolicy Bypass -File " + ps1File).execute();
        System.out.println("[2] 统计完成");

        wr.close();
        System.exit(0);
    }
}
