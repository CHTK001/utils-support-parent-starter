package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鍚庡彴鎵ц杩滅▼缁熻鑴氭湰锛岀粨鏋滃啓鍏?D:\fp-stat.txt銆? */
public class FpWinRmStatBg {
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
        System.out.println("=== 鎵ц杩滅▼缁熻 ===");
        // 鐢?start /b 鍚庡彴杩愯 PS1锛岀粨鏋滃啓鍏ユ枃浠?        var r = wr.exec().command("cmd.exe /c start /b powershell -ExecutionPolicy Bypass -File D:\\fp-stat.ps1").execute();
        System.out.println("鍚姩缁熻: exit=" + r.exitCode());
        wr.close();
        System.exit(0);
    }
}
