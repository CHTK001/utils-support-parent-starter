package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 瀹屾暣閲嶆柊瑙ｅ帇 jdk.zip锛堢敤 cmd 鐨?expand 鏇夸唬 powershell 鍙兘鏇寸ǔ锛夈€? */
public class FpWinRmRezip {
    public static void main(String[] args) throws Exception {
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== connected ===");

        // 鍒犻櫎宸叉湁涓嶅畬鏁磋В鍘?        var r0 = winrm.exec().command("cmd.exe /c rmdir /s /q C:\\jdk\\jdk21.0.12_8").execute();
        System.out.println("鍒犻櫎鏃х洰褰? exit=" + r0.exitCode() + " [" + r0.stdout() + r0.stderr() + "]");

        // 鐢?PowerShell Expand-Archive 閲嶆柊瑙ｅ帇
        var r1 = winrm.exec().command("powershell -Command \"Expand-Archive -Path C:\\jdk\\jdk.zip -DestinationPath C:\\jdk -Force\"").execute();
        System.out.println("瑙ｅ帇: exit=" + r1.exitCode() + " stdout=[" + r1.stdout() + "] stderr=[" + r1.stderr() + "]");

        // 楠岃瘉 lib/jvm.cfg
        var r2 = winrm.exec().command("cmd.exe /c dir C:\\jdk\\jdk21.0.12_8\\lib\\jvm.cfg").execute();
        System.out.println("jvm.cfg: [" + r2.stdout() + "]");

        winrm.close();
        System.exit(0);
    }
}