﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鎶婄粺璁¤剼鏈啓鎴愭湰鍦?exe 骞惰杩滅▼鐩存帴鎵ц锛岄伩鍏?PS1 鏉冮檺/缂栫爜闂銆? * 缁撴灉鍐欏叆 D:\fp-stat.txt銆? */
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
        System.out.println("=== 鎵ц杩滅▼缁熻 ===");

        // 鐩存帴鎶婄粺璁¤剼鏈啓鍒拌繙绋?D 鐩橈紙PowerShell WriteAllText锛?        var writeScript = wr.exec().command(
                "powershell -Command \"[System.IO.File]::WriteAllText('D:\\fp-stat.ps1', "
                + "\"$f = Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum; "
                + "Write-Output ('count=' + $f.Count + ' bytes_GB=' + [math]::Round($f.Sum/1GB,2)) "
                + "> 'D:\\fp-stat-result.txt'\""
        ).execute();
        System.out.println("[1] 鑴氭湰鍐欏叆: exit=" + writeScript.exitCode());

        // 鍚庡彴杩愯缁熻锛堢粨鏋滃啓鏂囦欢锛岄伩鍏?WinRM 瓒呮椂锛?        var run = wr.exec().command("cmd.exe /c start /b D:\\fp-stat.bat 2>nul").execute();
        System.out.println("[2] 缁熻宸插惎鍔? exit=" + run.exitCode());
        wr.close();
        System.exit(0);
    }
}
