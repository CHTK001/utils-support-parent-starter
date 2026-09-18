package com.chua.winrm.support.client;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmTcpEcho {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("WinRM connection OK, ready to deploy echo server");
        wr.close();
        System.exit(0);
    }
}
