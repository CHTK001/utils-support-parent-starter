﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 瀹氫綅杩滅▼ java.exe 骞堕獙璇併€? */
public class FpWinRmFindJava {
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

        // 鏌ユ壘 java.exe 璺緞
        var r1 = winrm.exec().command("cmd.exe /c dir /b /s C:\\jdk\\jdk21.0.12_8\\java.exe 2>&1").execute();
        System.out.println("java.exe: [" + r1.stdout() + "]");

        // 楠岃瘉 java -version
        var r2 = winrm.exec().command("\"C:\\jdk\\jdk21.0.12_8\\bin\\java.exe\" -version 2>&1").execute();
        System.out.println("java -version: [" + r2.stdout() + r2.stderr() + "]");

        winrm.close();
        System.exit(0);
    }
}