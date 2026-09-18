package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 查询 FPServer 计划任务的完整配置。
 */
public class FpWinRmQueryTask {
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
        var q = wr.exec().command("cmd.exe /c schtasks /query /tn FPServer /v /fo list").execute();
        System.out.println(q.stdout());
        wr.close();
        System.exit(0);
    }
}
