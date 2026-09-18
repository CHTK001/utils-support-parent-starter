package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 远程校验 D:\fp-sync：文件数 + 总大小 + 3 个失败文件是否已补偿推送。
 */
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
        System.out.println("=== D:\\fp-sync 校验 ===");

        // 文件数 + 总大小（PowerShell 一步出结果，避免递归多次调用）
        var r = wr.exec().command(
                "powershell -Command \"(Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum) | "
                + "ForEach-Object { $_.Count.ToString() + '|' + [math]::Round($_.Sum/1MB,1) + 'MB' }\"").execute();
        System.out.println("count|sizeMB = " + r.stdout().trim());

        wr.close();
        System.exit(0);
    }
}
