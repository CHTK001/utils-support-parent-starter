﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * FpWinRmDeep校验类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmDeepCheck {
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
        System.out.println("=== 褰撳墠鐩戝惉 9777 ===");
        var p1 = wr.exec().command("cmd.exe /c netstat -ano | findstr 9777").execute();
        System.out.println(p1.stdout());

        System.out.println("=== 鎵€鏈?java 杩涚▼鍙婂懡浠よ ===");
        var p2 = wr.exec().command("cmd.exe /c wmic process where \"name='java.exe'\" get ProcessId,CommandLine /format:list").execute();
        System.out.println(p2.stdout());

        System.out.println("=== server.log 淇敼鏃堕棿涓庡唴瀹?===");
        var p3 = wr.exec().command("cmd.exe /c dir /tc C:\\fp-push\\server.log").execute();
        System.out.println(p3.stdout());
        var p4 = wr.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("content=[" + p4.stdout() + "]");

        System.out.println("=== 璁″垝浠诲姟涓婃杩愯缁撴灉 ===");
        var p5 = wr.exec().command("cmd.exe /c schtasks /query /tn FPServer /v /fo list").execute();
        System.out.println(p5.stdout().substring(0, Math.min(800, p5.stdout().length())));

        wr.close();
        System.exit(0);
    }
}