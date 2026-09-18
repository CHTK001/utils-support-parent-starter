package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * FpWinRmKillAndRestart类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmKillAndRestart {
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
        // Kill by PID
        var r1 = wr.exec().command("taskkill /PID 14104 /F").execute();
        System.out.println("kill: " + r1.stdout().trim() + " | " + r1.stderr().trim());
        Thread.sleep(3000);
        // Verify
        var ps = wr.exec().command("cmd.exe /c tasklist /fi \"pid eq 14104\" /fo csv /nh").execute();
        System.out.println("post-kill: " + ps.stdout().trim());
        // Start new
        var r2 = wr.exec().command("schtasks /run /tn FPServer").execute();
        System.out.println("run: " + r2.stdout().trim());
        Thread.sleep(4000);
        // Log
        var log = wr.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("log: " + log.stdout().trim());
        wr.close();
        System.exit(0);
    }
}
