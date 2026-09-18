package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 把统计脚本写成本地 exe 并让远程直接执行，避免 PS1 权限/编码问题。
 * 结果写入 D:\fp-stat.txt。
 */
public class FpWinRmStatViaExe {
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
        System.out.println("=== 执行远程统计 ===");

        // 直接把统计脚本写到远程 D 盘（PowerShell WriteAllText）
        var writeScript = wr.exec().command(
                "powershell -Command \"[System.IO.File]::WriteAllText('D:\\fp-stat.ps1', "
                + "\"$f = Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum; "
                + "Write-Output ('count=' + $f.Count + ' bytes_GB=' + [math]::Round($f.Sum/1GB,2)) "
                + "> 'D:\\fp-stat-result.txt'\""
        ).execute();
        System.out.println("[1] 脚本写入: exit=" + writeScript.exitCode());

        // 后台运行统计（结果写文件，避免 WinRM 超时）
        var run = wr.exec().command("cmd.exe /c start /b D:\\fp-stat.bat 2>nul").execute();
        System.out.println("[2] 统计已启动: exit=" + run.exitCode());
        wr.close();
        System.exit(0);
    }
}
