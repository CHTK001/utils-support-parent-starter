package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmRunStat2 {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        var r = wr.exec().command("powershell -ExecutionPolicy Bypass -File C:\\fp-push\\count.ps1").execute();
        System.out.println("远程统计: " + r.stdout().trim());
        wr.close();
        System.exit(0);
    }
}