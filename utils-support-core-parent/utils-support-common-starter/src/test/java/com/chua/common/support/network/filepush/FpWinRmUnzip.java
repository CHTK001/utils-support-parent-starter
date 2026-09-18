package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 远程解压 jdk.zip 并定位 java。
 */
public class FpWinRmUnzip {
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

        // 解压 jdk.zip
        var r1 = winrm.exec().command("powershell -Command \"Expand-Archive -Path C:\\jdk\\jdk.zip -DestinationPath C:\\jdk -Force\"").execute();
        System.out.println("解压: exit=" + r1.exitCode() + " stdout=[" + r1.stdout() + "] stderr=[" + r1.stderr() + "]");

        // 查找 java.exe
        var r2 = winrm.exec().command("cmd.exe /c dir /b /s C:\\jdk\\java.exe").execute();
        System.out.println("查找 java.exe: [" + r2.stdout() + "]");

        winrm.close();
        System.exit(0);
    }
}