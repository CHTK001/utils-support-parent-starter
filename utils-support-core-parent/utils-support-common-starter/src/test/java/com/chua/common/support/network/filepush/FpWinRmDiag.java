package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 诊断 FPServer 任务失败原因：直接手动执行命令看报错。
 */
public class FpWinRmDiag {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("[1] connected");

        // 直接执行命令看输出
        String cmd = "cmd.exe /c C:\\jdk\\jdk21.0.12_8\\bin\\java.exe -version 2>&1";
        var r1 = wr.exec().command(cmd).execute();
        System.out.println("[2] java -version: " + r1.stdout().trim() + " | " + r1.stderr().trim());

        // 检查 classes 目录是否存在
        var r2 = wr.exec().command("cmd.exe /c if exist C:\\fp-push\\classes\\com\\chua\\common\\support\\network\\filepush\\FilePushServerMain.class echo EXISTS else echo MISSING").execute();
        System.out.println("[3] classes: " + r2.stdout().trim());

        // 检查 9777 是否被占用
        var r3 = wr.exec().command("cmd.exe /c netstat -ano | findstr :9777").execute();
        System.out.println("[4] port 9777: " + r3.stdout().trim());

        // 检查 D:\fp-sync 是否可写
        var r4 = wr.exec().command("cmd.exe /c dir D:\\fp-sync").execute();
        System.out.println("[5] D:\\fp-sync: " + r4.stdout().trim().substring(0, Math.min(200, r4.stdout().trim().length())));

        wr.close();
        System.exit(0);
    }
}
