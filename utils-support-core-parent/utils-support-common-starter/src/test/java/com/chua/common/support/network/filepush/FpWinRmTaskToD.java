package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * FpWinRmTask转为D类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmTaskToD {
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
        wr.exec().command("cmd.exe /c taskkill /f /im java.exe /t").execute();
        Thread.sleep(2000);
        wr.exec().command("cmd.exe /c schtasks /delete /tn FPServer /f").execute();
        String cmd = "cmd.exe /c schtasks /create /tn FPServer /tr \"C:\\jdk\\jdk21.0.12_8\\bin\\java.exe -cp C:\\fp-push\\classes com.chua.common.support.network.filepush.FilePushServerMain 9777 D:\\fp-sync\" /sc once /st 23:59 /f";
        var r = wr.exec().command(cmd).execute();
        System.out.println("create: " + r.stdout().trim());
        var r2 = wr.exec().command("cmd.exe /c schtasks /run /tn FPServer").execute();
        System.out.println("run: " + r2.stdout().trim());
        Thread.sleep(3000);
        var port = wr.exec().command("cmd.exe /c netstat -ano | findstr :9777").execute();
        System.out.println("port: " + port.stdout().trim());
        wr.close();
        System.exit(0);
    }
}
