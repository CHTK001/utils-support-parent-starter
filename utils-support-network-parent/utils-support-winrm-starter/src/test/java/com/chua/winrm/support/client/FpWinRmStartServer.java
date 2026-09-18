﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 绗竴姝ワ細浠呭惎鍔ㄨ繙绋?FilePushServer锛堝悗鍙帮級锛岄獙璇佺洃鍚€? */
public class FpWinRmStartServer {
    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    public static void main(String[] args) throws Exception {
        String sshTunnel = "http://172.16.9.194:5985/wsman";
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== connected ===");

        String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
        String CLS = "C:\\fp-push\\classes";
        String TARGET = "C:\\fp-received";

        // 鍋滄鏃ц繘绋?        var kill = winrm.exec().command("cmd.exe /c powershell -Command \"Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue\"").execute();
        System.out.println("鏉€鏃ц繘绋? exit=" + kill.exitCode() + " [" + kill.stdout().trim() + "]");

        // 鍚庡彴鍚姩 FilePushServer
        String cmd = "cmd.exe /c start /b \"FPServer\" \"" + JAVA + "\" --enable-preview -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain 9777 " + TARGET
                + " > C:\\fp-push\\server.log 2>&1";
        var r = winrm.exec().command(cmd).execute();
        System.out.println("鍚姩鍛戒护: exit=" + r.exitCode() + " stdout=[" + r.stdout().trim() + "] stderr=[" + r.stderr().trim() + "]");

        Thread.sleep(5000);

        // 鏌ョ湅 server.log
        var log = winrm.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("=== server.log ===");
        System.out.println(log.stdout());

        // 妫€鏌?java 杩涚▼
        var ps = winrm.exec().command("cmd.exe /c tasklist /fi \"imagename eq java.exe\" /fo list").execute();
        System.out.println("=== java 杩涚▼ ===");
        System.out.println(ps.stdout());

        // 妫€鏌ョ鍙ｇ洃鍚紙netstat锛?        var net = winrm.exec().command("cmd.exe /c netstat -ano | findstr 9777").execute();
        System.out.println("=== 9777 绔彛 ===");
        System.out.println(net.stdout());

        winrm.close();
        System.out.println("=== 瀹屾垚 ===");
        System.exit(0);
    }
}