package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鎶婃湰鍦板け璐ョ殑 3 涓枃浠惰ˉ鎺ㄥ埌杩滅▼ D:\fp-sync锛岀劧鍚庤繙绋嬫牎楠岀粺璁°€? */
public class FpWinRmFixVerify {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("=== connected ===");

        // 1. 鍐欑粺璁¤剼鏈埌杩滅▼
        wr.exec().command("cmd.exe /c del C:\\fp-push\\count.ps1 2>nul").execute();
        wr.exec().command("cmd.exe /c echo (Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum) | "
                + "ForEach-Object { \"count=$($_.Count) bytes=$([math]::Round($_.Sum/1GB,2))GB\" } > C:\\fp-push\\count.ps1").execute();
        System.out.println("[1] count.ps1 written");

        // 2. 鎵ц缁熻
        var r1 = wr.exec().command("powershell -ExecutionPolicy Bypass -File C:\\fp-push\\count.ps1").execute();
        System.out.println("[2] D:\\fp-sync 缁熻: " + r1.stdout().trim());

        wr.close();
        System.exit(0);
    }
}
