﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鐢?tar 鍚庡彴瑙ｅ帇 jdk.zip锛屼笉绛夌粨鏋溿€? */
public class FpWinRmTar {
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

        // 鐢?start /b 璋?tar 鍚庡彴瑙ｅ帇锛岀珛鍗宠繑鍥?        var r1 = winrm.exec().command("cmd.exe /c \"start /b cmd.exe /c tar -xf C:\\jdk\\jdk.zip -C C:\\jdk && echo DONE > C:\\jdk\\done.txt\"").execute();
        System.out.println("鍚庡彴瑙ｅ帇鍛戒护宸插彂閫? exit=" + r1.exitCode() + " [" + r1.stdout() + "] [" + r1.stderr() + "]");

        // 绛?5 绉掑悗妫€鏌?jvm.cfg 鏄惁鍑虹幇
        Thread.sleep(5000);
        var r2 = winrm.exec().command("cmd.exe /c if exist C:\\jdk\\jdk21.0.12_8\\lib\\jvm.cfg echo JVR_OK else echo JVR_MISSING").execute();
        System.out.println("jvm.cfg 妫€鏌? [" + r2.stdout().trim() + "]");

        var r3 = winrm.exec().command("cmd.exe /c if exist C:\\jdk\\done.txt echo FULL_DONE else echo STILL_EXTRACTING").execute();
        System.out.println("瑙ｅ帇瀹屾垚妫€鏌? [" + r3.stdout().trim() + "]");

        winrm.close();
        System.exit(0);
    }
}