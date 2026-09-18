package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
/**
 * FpWinRm运行Stat类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmRunStat {
    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
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