package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 在远程创建一个统计 EXE，避免 PowerShell 脚本权限/超时问题。
 * 直接用 cmd 的 for + 重定向写结果文件。
 */
public class FpWinRmStatViaBat {
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
        System.out.println("=== 创建统计 bat ===");

        // 写 bat 到远程 D 盘（通过 WinRM 的 PowerShell 写文件，避免编码问题）
        var writeBat = wr.exec().command(
                "powershell -Command \"Set-Content -Path 'D:\\fp-stat.bat' -Value @('"
                + "@echo off`n"
                + "dir /s /b D:\\fp-sync > D:\\fp-filelist.txt 2>nul`n"
                + "echo %~nx0 > D:\\fp-stat-result.txt`n"
                + "(Get-Content D:\\fp-filelist.txt | Measure-Object -Line).Lines | Out-File -Append D:\\fp-stat-result.txt -Encoding ASCII`n"
                + ") -Force\"").execute();
        System.out.println("[1] bat created: exit=" + writeBat.exitCode());

        // 后台运行 bat（统计文件列表，结果写到 fp-stat-result.txt）
        var run = wr.exec().command("cmd.exe /c start /b D:\\fp-stat.bat").execute();
        System.out.println("[2] bat started: exit=" + run.exitCode());
        wr.close();
        System.exit(0);
    }
}
