package com.chua.winrm.support.client;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmQueryTask {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        var r = wr.exec().command("schtasks /query /tn FPServer /v /fo list & schtasks /query /tn FPEcho /v /fo list").execute();
        System.out.println(r.stdout());
        wr.close();
        System.exit(0);
    }
}