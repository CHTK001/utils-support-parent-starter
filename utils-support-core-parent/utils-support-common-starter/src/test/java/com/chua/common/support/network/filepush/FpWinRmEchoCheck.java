package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
/**
 * FpWinRmEcho校验类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmEchoCheck {
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
        var t1 = wr.exec().command("cmd.exe /c schtasks /query /tn FPEcho /fo list").execute();
        System.out.println("FPEcho task: " + t1.stdout().trim());
        var t2 = wr.exec().command("cmd.exe /c schtasks /query /tn FPServer /fo list").execute();
        System.out.println("FPServer task: " + t2.stdout().trim());
        var p = wr.exec().command("cmd.exe /c netstat -ano | findstr LISTENING | findstr 9777").execute();
        System.out.println("9777: " + p.stdout().trim());
        var p2 = wr.exec().command("cmd.exe /c netstat -ano | findstr LISTENING | findstr 9999").execute();
        System.out.println("9999: " + p2.stdout().trim());
        var log = wr.exec().command("cmd.exe /c type C:\\fp-push\\echo.log 2>nul").execute();
        System.out.println("echo.log: " + log.stdout().trim());
        wr.close();
        System.exit(0);
    }
}