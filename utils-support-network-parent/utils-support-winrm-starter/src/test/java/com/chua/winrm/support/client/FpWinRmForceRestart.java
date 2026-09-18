package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 寮烘潃鍗犵敤 9777 鐨勬棫杩涚▼ + 閲嶅惎 FPServer + 绛夌鍙ｅ氨缁€? */
public class FpWinRmForceRestart {
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
        System.out.println("[1] connected");

        // 鏉€鎺夋墍鏈?java锛堝寘鎷崰鐢?9777 鐨勶級
        var kill = wr.exec().command("cmd.exe /c taskkill /f /im java.exe /t").execute();
        System.out.println("[2] kill: " + kill.stdout().trim() + " | " + kill.stderr().trim());
        Thread.sleep(2000);

        // 纭 9777 绌洪棽
        var check = wr.exec().command("cmd.exe /c netstat -ano | findstr :9777").execute();
        System.out.println("[3] port 9777 after kill: [" + check.stdout().trim() + "]");

        // 閲嶅惎 FPServer
        wr.exec().command("schtasks /run /tn FPServer").execute();
        System.out.println("[4] FPServer started");
        Thread.sleep(4000);

        // 楠岃瘉
        var port2 = wr.exec().command("cmd.exe /c netstat -ano | findstr :9777").execute();
        System.out.println("[5] port 9777 now: " + port2.stdout().trim());
        var log = wr.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("[6] log: " + log.stdout().trim());
        wr.close();
        System.exit(0);
    }
}
