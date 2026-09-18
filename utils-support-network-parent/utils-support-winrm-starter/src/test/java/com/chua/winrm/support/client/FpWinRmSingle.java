﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鍗曞懡浠ゆ祴璇曪細鐪嬪師濮?stdout/stderr/exitCode銆? */
public class FpWinRmSingle {
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

        // 鐢?ExecResult 瀹屾暣鏌ョ湅
        var r = winrm.exec().command("cmd.exe /c dir C:\\").execute();
        System.out.println("exitCode=[" + r.exitCode() + "]");
        System.out.println("stdout=[" + r.stdout() + "]");
        System.out.println("stderr=[" + r.stderr() + "]");

        // 绠€鍗曞懡浠?        var r2 = winrm.exec().command("echo test123").execute();
        System.out.println("echo exitCode=[" + r2.exitCode() + "] stdout=[" + r2.stdout() + "]");

        // powershell 鍗曞懡浠?        var r3 = winrm.exec().command("powershell -Command \"Write-Output ps-test\"").execute();
        System.out.println("ps exitCode=[" + r3.exitCode() + "] stdout=[" + r3.stdout() + "]");

        winrm.close();
        System.exit(0);
    }
}