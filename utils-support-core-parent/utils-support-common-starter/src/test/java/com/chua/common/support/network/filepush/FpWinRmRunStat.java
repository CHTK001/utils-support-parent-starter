package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmRunStat {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("[1] 创建统计任务...");
        wr.exec().command("schtasks /create /tn FPStat /tr D:\\fp-stat.bat /sc once /st 23:59 /f").execute();
        System.out.println("[2] 运行统计任务...");
        wr.exec().command("schtasks /run /tn FPStat").execute();
        System.out.println("[3] 完成，结果在 D:\\fp-stat-result.txt");
        wr.close();
        System.exit(0);
    }
}