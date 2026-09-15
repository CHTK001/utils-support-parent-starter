package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 第一步：仅启动远程 FilePushServer（后台），验证监听。
 */
public class FpWinRmStartServer {
    public static void main(String[] args) throws Exception {
        String sshTunnel = "http://172.16.9.194:5985/wsman";
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== connected ===");

        String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
        String CLS = "C:\\fp-push\\classes";
        String TARGET = "C:\\fp-received";

        // 停止旧进程
        var kill = winrm.exec().command("cmd.exe /c powershell -Command \"Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue\"").execute();
        System.out.println("杀旧进程: exit=" + kill.exitCode() + " [" + kill.stdout().trim() + "]");

        // 后台启动 FilePushServer
        String cmd = "cmd.exe /c start /b \"FPServer\" \"" + JAVA + "\" --enable-preview -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain 9777 " + TARGET
                + " > C:\\fp-push\\server.log 2>&1";
        var r = winrm.exec().command(cmd).execute();
        System.out.println("启动命令: exit=" + r.exitCode() + " stdout=[" + r.stdout().trim() + "] stderr=[" + r.stderr().trim() + "]");

        Thread.sleep(5000);

        // 查看 server.log
        var log = winrm.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("=== server.log ===");
        System.out.println(log.stdout());

        // 检查 java 进程
        var ps = winrm.exec().command("cmd.exe /c tasklist /fi \"imagename eq java.exe\" /fo list").execute();
        System.out.println("=== java 进程 ===");
        System.out.println(ps.stdout());

        // 检查端口监听（netstat）
        var net = winrm.exec().command("cmd.exe /c netstat -ano | findstr 9777").execute();
        System.out.println("=== 9777 端口 ===");
        System.out.println(net.stdout());

        winrm.close();
        System.out.println("=== 完成 ===");
        System.exit(0);
    }
}