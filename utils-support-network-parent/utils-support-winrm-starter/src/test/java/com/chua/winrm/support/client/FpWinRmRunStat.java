package com.chua.winrm.support.client;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmRunStat {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("[1] 鍒涘缓缁熻浠诲姟...");
        wr.exec().command("schtasks /create /tn FPStat /tr D:\\fp-stat.bat /sc once /st 23:59 /f").execute();
        System.out.println("[2] 杩愯缁熻浠诲姟...");
        wr.exec().command("schtasks /run /tn FPStat").execute();
        System.out.println("[3] 瀹屾垚锛岀粨鏋滃湪 D:\\fp-stat-result.txt");
        wr.close();
        System.exit(0);
    }
}