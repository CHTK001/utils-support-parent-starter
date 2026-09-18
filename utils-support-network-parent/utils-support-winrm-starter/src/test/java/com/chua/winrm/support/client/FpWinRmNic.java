package com.chua.winrm.support.client;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmNic {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        var r1 = wr.exec().command("powershell -NoProfile -Command \"Get-NetAdapter | Where Status -eq Up | Select Name,LinkSpeed | Format-Table\"").execute();
        System.out.println("NETADAPTER: " + r1.stdout().trim());
        wr.close();
        System.exit(0);
    }
}