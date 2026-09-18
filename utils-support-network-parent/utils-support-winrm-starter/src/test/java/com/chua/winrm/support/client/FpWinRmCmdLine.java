package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

public class FpWinRmCmdLine {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("=== PID 19824 鍛戒护琛?===");
        var p1 = wr.exec().command("powershell -Command \"(Get-CimInstance Win32_Process -Filter 'ProcessId=19824').CommandLine\"").execute();
        System.out.println("[" + p1.stdout().trim() + "]");
        System.out.println("stderr=[" + p1.stderr().trim() + "]");

        System.out.println("=== 鍏ㄩ儴 java 鍛戒护琛?===");
        var p2 = wr.exec().command("powershell -Command \"Get-CimInstance Win32_Process -Filter \\\"Name='java.exe'\\\" | ForEach-Object { $_.ProcessId.ToString() + ' :: ' + $_.CommandLine }\"").execute();
        System.out.println("[" + p2.stdout() + "]");
        System.out.println("stderr=[" + p2.stderr().trim() + "]");

        wr.close();
        System.exit(0);
    }
}