package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鐢ㄨ鍒掍换鍔″惎鍔ㄨ繙绋?FilePushServer锛堥伩鍏嶉樆濉?WinRM 浼氳瘽锛夈€? */
public class FpWinRmSchtask {
    public static void main(String[] args) throws Exception {
        System.out.println("[1] connect...");
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("[2] connected");

        String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
        String CLS = "C:\\fp-push\\classes";
        String TARGET = "C:\\fp-received";

        // 鍒犻櫎鏃т换鍔?        winrm.exec().command("schtasks /delete /tn FPServer /f").execute();
        System.out.println("[3] old task removed");

        // 鍒涘缓璁″垝浠诲姟锛氱珛鍗宠繍琛?java
        String create = "schtasks /create /tn FPServer /tr \"\\\"" + JAVA
                + "\\\" --enable-preview -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain 9777 " + TARGET
                + " > C:\\fp-push\\server.log 2>&1\" /sc once /st 23:59 /f";
        var r = winrm.exec().command(create).execute();
        System.out.println("[4] create: exit=" + r.exitCode() + " [" + r.stdout().trim() + "] [" + r.stderr().trim() + "]");

        // 杩愯浠诲姟
        var r2 = winrm.exec().command("schtasks /run /tn FPServer").execute();
        System.out.println("[5] run: exit=" + r2.exitCode() + " [" + r2.stdout().trim() + "] [" + r2.stderr().trim() + "]");

        winrm.close();
        System.out.println("[6] done");
        System.exit(0);
    }
}