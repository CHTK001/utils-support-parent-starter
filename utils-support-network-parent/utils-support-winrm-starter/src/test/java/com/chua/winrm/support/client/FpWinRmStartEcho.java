﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鍦ㄨ繙绋?9999 绔彛鍚姩 EchoServer锛堢函 TCP 瑁搁€熸祴璇曠敤锛夈€? */
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

        // 鏉€鎵€鏈?java锛堝厛鏉€ echo 鍜?FPServer锛岀粺涓€閲嶅惎锛?        wr.exec().command("cmd.exe /c taskkill /f /im java.exe /t 2>nul").execute();
        Thread.sleep(1000);

        // 閲嶅惎 FPServer锛?777 鈫?D:\fp-sync锛?        wr.exec().command("cmd.exe /c schtasks /run /tn FPServer").execute();
        Thread.sleep(2000);
        System.out.println("[2] FPServer restarted");

        // 鍚姩 EchoServer锛?999锛?        var r = wr.exec().command("cmd.exe /c start \"\" /b C:\\jdk\\jdk21.0.12_8\\bin\\java.exe -cp C:\\fp-push EchoServer 9999 > C:\\fp-push\\echo.log 2>&1").execute();
        System.out.println("[3] echo start: exit=" + r.exitCode() + " out=[" + r.stdout().trim() + "]");
        Thread.sleep(2000);

        // 楠岃瘉 9999
        var check = wr.exec().command("cmd.exe /c netstat -ano | findstr :9999").execute();
        System.out.println("[4] port 9999: " + check.stdout().trim());

        wr.close();
        System.exit(0);
    }
}
