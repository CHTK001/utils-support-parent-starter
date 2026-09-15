package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

public class FpWinRmCheck {
    public static void main(String[] args) throws Exception {
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== java processes ===");
        var p1 = winrm.exec().command("cmd.exe /c tasklist /fi \"imagename eq java.exe\" /fo list").execute();
        System.out.println(p1.stdout());

        System.out.println("=== port 9777 ===");
        var p2 = winrm.exec().command("cmd.exe /c netstat -ano | findstr 9777").execute();
        System.out.println(p2.stdout());

        System.out.println("=== server.log mtime ===");
        var p3 = winrm.exec().command("cmd.exe /c dir C:\\fp-push\\server.log").execute();
        System.out.println(p3.stdout());

        winrm.close();
        System.exit(0);
    }
}