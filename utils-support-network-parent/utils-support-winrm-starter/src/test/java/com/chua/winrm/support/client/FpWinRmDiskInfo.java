package com.chua.winrm.support.client;
import com.chua.winrm.support.client.WinRmExecClient;
/**
 * FpWinRmDiskInfo类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmDiskInfo {
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
        var r = wr.exec().command("powershell -NoProfile -Command \"Get-Disk | Select Number, FriendlyName, MediaType, Size | Format-Table\"").execute();
        System.out.println(r.stdout());
        wr.close();
        System.exit(0);
    }
}