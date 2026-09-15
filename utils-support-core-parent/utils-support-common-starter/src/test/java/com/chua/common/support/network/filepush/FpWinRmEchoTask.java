package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmEchoTask {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("[1] connected");

        // 杀 java
        wr.exec().command("cmd.exe /c taskkill /f /im java.exe /t 2>nul").execute();
        Thread.sleep(1500);

        // FPServer → D:\fp-sync
        wr.exec().command("cmd.exe /c schtasks /delete /tn FPServer /f 2>nul").execute();
        var c1 = wr.exec().command("cmd.exe /c schtasks /create /tn FPServer /tr \"C:\\jdk\\jdk21.0.12_8\\bin\\java.exe -cp C:\\fp-push com.chua.common.support.network.filepush.FilePushServerMain 9777 D:\\fp-sync\" /sc once /st 23:59 /f").execute();
        System.out.println("[2] FPServer create: " + c1.stdout().trim());
        wr.exec().command("cmd.exe /c schtasks /run /tn FPServer").execute();
        Thread.sleep(3000);

        // FPEcho → 9999
        wr.exec().command("cmd.exe /c schtasks /delete /tn FPEcho /f 2>nul").execute();
        var c2 = wr.exec().command("cmd.exe /c schtasks /create /tn FPEcho /tr \"C:\\jdk\\jdk21.0.12_8\\bin\\java.exe -cp C:\\fp-push EchoServer 9999\" /sc once /st 23:59 /f").execute();
        System.out.println("[3] FPEcho create: " + c2.stdout().trim());
        wr.exec().command("cmd.exe /c schtasks /run /tn FPEcho").execute();
        Thread.sleep(2000);

        var p = wr.exec().command("cmd.exe /c netstat -ano | findstr LISTENING | findstr \"9777\\|9999\"").execute();
        System.out.println("[4] ports: " + p.stdout().trim());
        wr.close();
        System.exit(0);
    }
}