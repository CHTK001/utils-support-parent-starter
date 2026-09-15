package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 确认远程 JDK 并测试 Java。
 */
public class FpWinRmJdk {
    public static void main(String[] args) throws Exception {
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== connected ===");

        // 查看 C:\jdk 内容
        var r1 = winrm.exec().command("cmd.exe /c dir /b C:\\jdk").execute();
        System.out.println("C:\\jdk 内容: [" + r1.stdout() + "]");

        // 尝试绝对路径 java -version
        var r2 = winrm.exec().command("\"C:\\jdk\\bin\\java.exe\" -version 2>&1").execute();
        System.out.println("C:\\jdk\\bin\\java.exe -version: [" + r2.stdout() + "|" + r2.stderr() + "]");

        // 尝试其他路径
        var r3 = winrm.exec().command("cmd.exe /c dir /b /s C:\\jdk\\*.exe").execute();
        System.out.println("jdk 下 exe: [" + r3.stdout() + "]");

        // 处理器/内存
        var r4 = winrm.exec().command("wmic cpu get NumberOfLogicalProcessors").execute();
        System.out.println("CPU: [" + r4.stdout() + "]");

        winrm.close();
        System.exit(0);
    }
}