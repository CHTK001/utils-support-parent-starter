package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鏉€鏃?Java 杩涚▼骞堕噸寤?FPServer 浠诲姟鎸囧悜 D:\fp-sync銆? */
public class FpWinRmRestartToD {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("[1] connected");

        // 鏉€鎵€鏈?java
        wr.exec().command("cmd.exe /c taskkill /f /im java.exe /t").execute();
        Thread.sleep(2000);

        // 鍒犳棫浠诲姟
        wr.exec().command("schtasks /delete /tn FPServer /f").execute();
        System.out.println("[2] old task deleted");

        String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
        String CLS = "C:\\fp-push\\classes";
        String create = "schtasks /create /tn FPServer /tr \"\"" + JAVA
                + "\" -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain 9777 D:\\fp-sync > C:\\fp-push\\server.log 2>&1\" /sc once /st 23:59 /f";
        var r = wr.exec().command(create).execute();
        System.out.println("[3] create: " + r.stdout().trim() + " " + r.stderr().trim());

        var r2 = wr.exec().command("schtasks /run /tn FPServer").execute();
        System.out.println("[4] run: " + r2.stdout().trim());
        Thread.sleep(4000);

        var log = wr.exec().command("cmd.exe /c type C:\\fp-push\\server.log 2>nul").execute();
        System.out.println("[5] log: " + log.stdout().trim());
        wr.close();
        System.exit(0);
    }
}
