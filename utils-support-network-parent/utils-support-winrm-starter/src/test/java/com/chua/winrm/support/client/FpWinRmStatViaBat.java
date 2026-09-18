﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鍦ㄨ繙绋嬪垱寤轰竴涓粺璁?EXE锛岄伩鍏?PowerShell 鑴氭湰鏉冮檺/瓒呮椂闂銆? * 鐩存帴鐢?cmd 鐨?for + 閲嶅畾鍚戝啓缁撴灉鏂囦欢銆? */
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
        System.out.println("=== 鍒涘缓缁熻 bat ===");

        // 鍐?bat 鍒拌繙绋?D 鐩橈紙閫氳繃 WinRM 鐨?PowerShell 鍐欐枃浠讹紝閬垮厤缂栫爜闂锛?        var writeBat = wr.exec().command(
                "powershell -Command \"Set-Content -Path 'D:\\fp-stat.bat' -Value @('"
                + "@echo off`n"
                + "dir /s /b D:\\fp-sync > D:\\fp-filelist.txt 2>nul`n"
                + "echo %~nx0 > D:\\fp-stat-result.txt`n"
                + "(Get-Content D:\\fp-filelist.txt | Measure-Object -Line).Lines | Out-File -Append D:\\fp-stat-result.txt -Encoding ASCII`n"
                + ") -Force\"").execute();
        System.out.println("[1] bat created: exit=" + writeBat.exitCode());

        // 鍚庡彴杩愯 bat锛堢粺璁℃枃浠跺垪琛紝缁撴灉鍐欏埌 fp-stat-result.txt锛?        var run = wr.exec().command("cmd.exe /c start /b D:\\fp-stat.bat").execute();
        System.out.println("[2] bat started: exit=" + run.exitCode());
        wr.close();
        System.exit(0);
    }
}
