package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
/**
 * FpWinRmBoot类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmBoot {
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
        wr.exec().command("cmd.exe /c schtasks /run /tn FPServer").execute();
        Thread.sleep(3000);
        var p = wr.exec().command("cmd.exe /c netstat -ano | findstr :9777 | findstr LISTENING").execute();
        System.out.println("[2] 9777: " + p.stdout().trim());
        wr.exec().command("cmd.exe /c schtasks /run /tn FPEcho").execute();
        Thread.sleep(2000);
        var p2 = wr.exec().command("cmd.exe /c netstat -ano | findstr :9999 | findstr LISTENING").execute();
        System.out.println("[3] 9999: " + p2.stdout().trim());
        wr.close();
        System.exit(0);
    }
}