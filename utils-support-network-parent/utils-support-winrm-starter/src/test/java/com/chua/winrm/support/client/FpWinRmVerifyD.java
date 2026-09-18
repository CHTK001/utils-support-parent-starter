package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 杩滅▼鏍￠獙 D:\fp-sync锛氭枃浠舵暟 + 鎬诲ぇ灏?+ 3 涓け璐ユ枃浠舵槸鍚﹀凡琛ュ伩鎺ㄩ€併€? */
public class FpWinRmVerifyD {
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
        System.out.println("=== D:\\fp-sync 鏍￠獙 ===");

        // 鏂囦欢鏁?+ 鎬诲ぇ灏忥紙PowerShell 涓€姝ュ嚭缁撴灉锛岄伩鍏嶉€掑綊澶氭璋冪敤锛?        var r = wr.exec().command(
                "powershell -Command \"(Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum) | "
                + "ForEach-Object { $_.Count.ToString() + '|' + [math]::Round($_.Sum/1MB,1) + 'MB' }\"").execute();
        System.out.println("count|sizeMB = " + r.stdout().trim());

        wr.close();
        System.exit(0);
    }
}
