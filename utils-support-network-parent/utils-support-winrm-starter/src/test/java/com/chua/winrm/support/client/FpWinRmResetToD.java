﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 1. 鏉€鎺夋棫 Java 杩涚▼ 2. 娓呯┖ D:\fp-sync 3. 閲嶅惎鍒?D:\fp-sync 4. 楠岃瘉
 */
public class FpWinRmResetToD {
    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    public static void main(String[] args) throws Exception {
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== connected ===");

        // 1. 鏉€鏃?Java 杩涚▼
        var kill = winrm.exec().command("cmd.exe /c powershell -Command \"Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue\"").execute();
        System.out.println("鏉€杩涚▼: " + kill.stdout().trim());
        Thread.sleep(2000);

        // 2. 鍒犻櫎鏃т换鍔″畾涔?        winrm.exec().command("schtasks /delete /tn FPServer /f").execute();
        System.out.println("鍒犻櫎鏃т换鍔?);

        // 3. 娓呯┖ C:\fp-received锛堥噴鏀?C 鐩橈級
        var clean = winrm.exec().command("cmd.exe /c rmdir /s /q C:\\fp-received 2>nul").execute();
        System.out.println("娓呯┖C:\\fp-received: exit=" + clean.exitCode());

        // 4. 纭繚 D:\fp-sync 鐩綍绌?        winrm.exec().command("cmd.exe /c rmdir /s /q D:\\fp-sync 2>nul").execute();
        winrm.exec().command("cmd.exe /c mkdir D:\\fp-sync").execute();
        System.out.println("D:\\fp-sync 宸插缓");

        // 5. 鏂拌鍒掍换鍔?鈫?D:\fp-sync
        String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
        String CLS  = "C:\\fp-push\\classes";
        String cmd  = "schtasks /create /tn FPServer /tr \"\\\"" + JAVA
                + "\\\" --enable-preview -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain 9777 D:\\fp-sync"
                + " > C:\\fp-push\\server.log 2>&1\" /sc once /st 23:59 /f";
        var r = winrm.exec().command(cmd).execute();
        System.out.println("鍒涘缓浠诲姟: " + r.stdout().trim());

        var r2 = winrm.exec().command("schtasks /run /tn FPServer").execute();
        System.out.println("杩愯浠诲姟: " + r2.stdout().trim());
        Thread.sleep(4000);

        // 6. 妫€鏌?server.log 纭鐩爣
        var log = winrm.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("=== server.log ===");
        System.out.println(log.stdout().trim());

        // 7. 楠岃瘉 9777 绔彛
        var port = winrm.exec().command("cmd.exe /c netstat -ano | findstr 9777").execute();
        System.out.println("=== port 9777 ===");
        System.out.println(port.stdout().trim());

        winrm.close();
        System.out.println("=== done ===");
        System.exit(0);
    }
}