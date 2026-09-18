package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 后台执行远程统计脚本，结果写入 D:\fp-stat.txt。
 */
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
        System.out.println("=== 执行远程统计 ===");
        // 用 start /b 后台运行 PS1，结果写入文件
        var r = wr.exec().command("cmd.exe /c start /b powershell -ExecutionPolicy Bypass -File D:\\fp-stat.ps1").execute();
        System.out.println("启动统计: exit=" + r.exitCode());
        wr.close();
        System.exit(0);
    }
}
