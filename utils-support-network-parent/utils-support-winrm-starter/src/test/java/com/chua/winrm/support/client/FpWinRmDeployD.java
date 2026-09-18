package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 閲嶅惎杩滅▼ FilePushServer 鈫?鐩爣 D:\fp-sync锛堢嫭绔嬬洰褰曚究浜庢暣浣撳垹闄わ級銆? */
public class FpWinRmDeployD {
    public static void main(String[] args) throws Exception {
        System.out.println("[1] connect...");
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("[2] connected");

        // 寤虹洰褰?        winrm.exec().command("cmd.exe /c mkdir D:\\fp-sync").execute();
        System.out.println("[3] D:\\fp-sync 宸插缓");

        // 鍒犻櫎鏃ц鍒掍换鍔?        winrm.exec().command("schtasks /delete /tn FPServer /f").execute();

        // 鍒涘缓鏂颁换鍔?鈫?鐩爣 D:\fp-sync
        String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
        String CLS = "C:\\fp-push\\classes";
        String create = "schtasks /create /tn FPServer /tr \"\\\"" + JAVA
                + "\\\" --enable-preview -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain 9777 D:\\fp-sync"
                + " > C:\\fp-push\\server.log 2>&1\" /sc once /st 23:59 /f";
        var r = winrm.exec().command(create).execute();
        System.out.println("[4] create: exit=" + r.exitCode() + " [" + r.stdout().trim() + "]");

        var r2 = winrm.exec().command("schtasks /run /tn FPServer").execute();
        System.out.println("[5] run: exit=" + r2.exitCode() + " [" + r2.stdout().trim() + "]");

        winrm.close();
        System.out.println("[6] done");
        System.exit(0);
    }
}