package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * WinRM 逐步探测：定位挂起点。
 */
public class FpWinRmStep {
    public static void main(String[] args) throws Exception {
        System.out.println("[1] 构建 WinRmExecClient...");
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        System.out.println("[2] connect()...");
        winrm.connect();
        System.out.println("[3] 连接成功，执行 echo...");
        long t0 = System.currentTimeMillis();
        String r1 = winrm.exec().command("echo hello").executeAndGetOutput();
        System.out.println("[4] echo 耗时 " + (System.currentTimeMillis() - t0) + "ms，结果=[" + r1 + "]");

        t0 = System.currentTimeMillis();
        String r2 = winrm.exec().command("hostname").executeAndGetOutput();
        System.out.println("[5] hostname 耗时 " + (System.currentTimeMillis() - t0) + "ms，结果=[" + r2 + "]");

        t0 = System.currentTimeMillis();
        String r3 = winrm.exec().command("java -version 2>&1").executeAndGetOutput();
        System.out.println("[6] java -version 耗时 " + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("    java: [" + r3 + "]");

        System.out.println("[7] close()...");
        winrm.close();
        System.out.println("[8] 完成");
        System.exit(0);
    }
}
