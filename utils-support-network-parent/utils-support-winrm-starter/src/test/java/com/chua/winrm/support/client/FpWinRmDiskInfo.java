package com.chua.winrm.support.client;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmDiskInfo {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        var r = wr.exec().command("powershell -NoProfile -Command \"Get-Disk | Select Number, FriendlyName, MediaType, Size | Format-Table\"").execute();
        System.out.println(r.stdout());
        wr.close();
        System.exit(0);
    }
}