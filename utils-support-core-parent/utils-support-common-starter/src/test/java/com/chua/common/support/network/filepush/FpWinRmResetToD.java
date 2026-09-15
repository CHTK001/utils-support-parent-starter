package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 1. 杀掉旧 Java 进程 2. 清空 D:\fp-sync 3. 重启到 D:\fp-sync 4. 验证
 */
public class FpWinRmResetToD {
    public static void main(String[] args) throws Exception {
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== connected ===");

        // 1. 杀旧 Java 进程
        var kill = winrm.exec().command("cmd.exe /c powershell -Command \"Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue\"").execute();
        System.out.println("杀进程: " + kill.stdout().trim());
        Thread.sleep(2000);

        // 2. 删除旧任务定义
        winrm.exec().command("schtasks /delete /tn FPServer /f").execute();
        System.out.println("删除旧任务");

        // 3. 清空 C:\fp-received（释放 C 盘）
        var clean = winrm.exec().command("cmd.exe /c rmdir /s /q C:\\fp-received 2>nul").execute();
        System.out.println("清空C:\\fp-received: exit=" + clean.exitCode());

        // 4. 确保 D:\fp-sync 目录空
        winrm.exec().command("cmd.exe /c rmdir /s /q D:\\fp-sync 2>nul").execute();
        winrm.exec().command("cmd.exe /c mkdir D:\\fp-sync").execute();
        System.out.println("D:\\fp-sync 已建");

        // 5. 新计划任务 → D:\fp-sync
        String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
        String CLS  = "C:\\fp-push\\classes";
        String cmd  = "schtasks /create /tn FPServer /tr \"\\\"" + JAVA
                + "\\\" --enable-preview -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain 9777 D:\\fp-sync"
                + " > C:\\fp-push\\server.log 2>&1\" /sc once /st 23:59 /f";
        var r = winrm.exec().command(cmd).execute();
        System.out.println("创建任务: " + r.stdout().trim());

        var r2 = winrm.exec().command("schtasks /run /tn FPServer").execute();
        System.out.println("运行任务: " + r2.stdout().trim());
        Thread.sleep(4000);

        // 6. 检查 server.log 确认目标
        var log = winrm.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("=== server.log ===");
        System.out.println(log.stdout().trim());

        // 7. 验证 9777 端口
        var port = winrm.exec().command("cmd.exe /c netstat -ano | findstr 9777").execute();
        System.out.println("=== port 9777 ===");
        System.out.println(port.stdout().trim());

        winrm.close();
        System.out.println("=== done ===");
        System.exit(0);
    }
}