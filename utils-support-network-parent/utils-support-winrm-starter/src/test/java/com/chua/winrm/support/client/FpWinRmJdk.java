package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 纭杩滅▼ JDK 骞舵祴璇?Java銆? */
public class FpWinRmJdk {
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

        // 鏌ョ湅 C:\jdk 鍐呭
        var r1 = winrm.exec().command("cmd.exe /c dir /b C:\\jdk").execute();
        System.out.println("C:\\jdk 鍐呭: [" + r1.stdout() + "]");

        // 灏濊瘯缁濆璺緞 java -version
        var r2 = winrm.exec().command("\"C:\\jdk\\bin\\java.exe\" -version 2>&1").execute();
        System.out.println("C:\\jdk\\bin\\java.exe -version: [" + r2.stdout() + "|" + r2.stderr() + "]");

        // 灏濊瘯鍏朵粬璺緞
        var r3 = winrm.exec().command("cmd.exe /c dir /b /s C:\\jdk\\*.exe").execute();
        System.out.println("jdk 涓?exe: [" + r3.stdout() + "]");

        // 澶勭悊鍣?鍐呭瓨
        var r4 = winrm.exec().command("wmic cpu get NumberOfLogicalProcessors").execute();
        System.out.println("CPU: [" + r4.stdout() + "]");

        winrm.close();
        System.exit(0);
    }
}