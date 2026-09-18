package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

public class FpWinRmTaskToD {
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
