package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 蹇€熼噸鍚細鏉€鏃ц繘绋?鈫?鍚姩鍒?D:\fp-sync
 */
public class FpWinRmQuickRestart {
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
        System.out.println("[1] connected");

        // 鏉€ java 杩涚▼
        winrm.exec().command("cmd.exe /c taskkill /f /im java.exe /t").execute();
        Thread.sleep(2000);
        System.out.println("[2] killed java");

        // 鍒犳棫浠诲姟锛屽缓鏂颁换鍔?        winrm.exec().command("schtasks /delete /tn FPServer /f").execute();
        winrm.exec().command("cmd.exe /c mkdir D:\\fp-sync").execute();
        String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
        String CLS = "C:\\fp-push\\classes";
        String create = "schtasks /create /tn FPServer /tr \"\\\"" + JAVA
                + "\\\" --enable-preview -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain 9777 D:\\fp-sync"
                + " > C:\\fp-push\\server.log 2>&1\" /sc once /st 23:59 /f";
        winrm.exec().command(create).execute();
        System.out.println("[3] task created");

        winrm.exec().command("schtasks /run /tn FPServer").execute();
        Thread.sleep(4000);
        System.out.println("[4] task started");

        // 楠岃瘉
        var log = winrm.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println(log.stdout().trim());

        winrm.close();
        System.exit(0);
    }
}