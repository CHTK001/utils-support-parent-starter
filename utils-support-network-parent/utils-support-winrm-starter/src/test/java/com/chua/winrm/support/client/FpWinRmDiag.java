﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 璇婃柇 FPServer 浠诲姟澶辫触鍘熷洜锛氱洿鎺ユ墜鍔ㄦ墽琛屽懡浠ょ湅鎶ラ敊銆? */
public class FpWinRmDiag {
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

        // 鐩存帴鎵ц鍛戒护鐪嬭緭鍑?        String cmd = "cmd.exe /c C:\\jdk\\jdk21.0.12_8\\bin\\java.exe -version 2>&1";
        var r1 = wr.exec().command(cmd).execute();
        System.out.println("[2] java -version: " + r1.stdout().trim() + " | " + r1.stderr().trim());

        // 妫€鏌?classes 鐩綍鏄惁瀛樺湪
        var r2 = wr.exec().command("cmd.exe /c if exist C:\\fp-push\\classes\\com\\chua\\common\\support\\network\\filepush\\FilePushServerMain.class echo EXISTS else echo MISSING").execute();
        System.out.println("[3] classes: " + r2.stdout().trim());

        // 妫€鏌?9777 鏄惁琚崰鐢?        var r3 = wr.exec().command("cmd.exe /c netstat -ano | findstr :9777").execute();
        System.out.println("[4] port 9777: " + r3.stdout().trim());

        // 妫€鏌?D:\fp-sync 鏄惁鍙啓
        var r4 = wr.exec().command("cmd.exe /c dir D:\\fp-sync").execute();
        System.out.println("[5] D:\\fp-sync: " + r4.stdout().trim().substring(0, Math.min(200, r4.stdout().trim().length())));

        wr.close();
        System.exit(0);
    }
}
