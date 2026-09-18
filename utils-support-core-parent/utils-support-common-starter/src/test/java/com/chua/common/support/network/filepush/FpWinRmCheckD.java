package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
/**
 * FpWinRm校验D类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmCheckD {
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
        var r = wr.exec().command("cmd.exe /c dir D:\\fp-sync\\big_*.dat /-c 2>nul").execute();
        System.out.println("D盘 big_*.dat: " + r.stdout().trim());
        var r2 = wr.exec().command("cmd.exe /c dir D:\\fp-sync /b /s 2>nul | find /c /v """"").execute();
        System.out.println("D:\\fp-sync 总文件数: " + r2.stdout().trim());
        wr.close();
        System.exit(0);
    }
}