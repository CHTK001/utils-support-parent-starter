package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmCheckD {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        var r = wr.exec().command("cmd.exe /c dir D:\\fp-sync\\big_*.dat /-c 2>nul").execute();
        System.out.println("D盘 big_*.dat: " + r.stdout().trim());
        var r2 = wr.exec().command("cmd.exe /c dir D:\\fp-sync /b /s 2>nul | find /c /v """"").execute();
        System.out.println("D:\\fp-sync 总文件数: " + r2.stdout().trim());
        wr.close();
        System.exit(0);
    }
}