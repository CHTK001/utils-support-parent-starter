package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 妫€鏌ヨ繙绋?FPServer 浠诲姟鐘舵€侊紙涓轰粈涔?stat 鑴氭湰娌′骇鐢熻緭鍑猴級銆? */
public class FpWinRmTaskCheck {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("=== FPServer 浠诲姟鐘舵€?===");
        var q = wr.exec().command("schtasks /query /tn FPServer /fo list").execute();
        System.out.println(q.stdout().trim());
        System.out.println("=== stat.ps1 鏄惁瀛樺湪 ===");
        var e1 = wr.exec().command("cmd.exe /c if exist C:\\fp-push\\stat.ps1 echo EXISTS else echo MISSING").execute();
        System.out.println(e1.stdout().trim());
        System.out.println("=== stat-out.txt 鏄惁瀛樺湪 ===");
        var e2 = wr.exec().command("cmd.exe /c if exist C:\\fp-push\\stat-out.txt echo EXISTS else echo MISSING").execute();
        System.out.println(e2.stdout().trim());
        wr.close();
        System.exit(0);
    }
}
