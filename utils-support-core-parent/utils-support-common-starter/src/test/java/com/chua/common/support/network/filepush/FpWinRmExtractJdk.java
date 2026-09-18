package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 用 start 后台完整解压 jdk.zip，轮询完成状态。
 */
public class FpWinRmExtractJdk {
    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    public static void main(String[] args) throws Exception {
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== connected ===");

        // 1. 删除不完整目录
        var r0 = winrm.exec().command("cmd.exe /c rmdir /s /q C:\\jdk\\jdk21.0.12_8 2>nul").execute();
        System.out.println("删除旧目录 exit=" + r0.exitCode());

        // 2. 后台解压（start /b 立即返回）
        var r1 = winrm.exec().command("cmd.exe /c \"start /b cmd.exe /c tar -xf C:\\jdk\\jdk.zip -C C:\\jdk && echo DONE> C:\\jdk\\done.txt\" >nul 2>nul").execute();
        System.out.println("后台解压已启动 exit=" + r1.exitCode() + " [" + r1.stdout() + "] [" + r1.stderr() + "]");

        // 3. 轮询完成 (最多 60s)
        boolean done = false;
        for (int i = 0; i < 12; i++) {
            Thread.sleep(5000);
            var chk = winrm.exec().command("cmd.exe /c if exist C:\\jdk\\done.txt (echo DONE) else (echo WAIT)").execute();
            String s = chk.stdout().trim();
            System.out.println("  轮询 " + (i+1) + ": " + s);
            if ("DONE".equals(s)) {
                done = true;
                break;
            }
        }

        // 4. 验证 jvm.cfg 和 java -version
        if (done) {
            var r2 = winrm.exec().command("cmd.exe /c dir /b C:\\jdk\\jdk21.0.12_8\\lib\\jvm.cfg").execute();
            System.out.println("jvm.cfg: [" + r2.stdout().trim() + "]");
            var r3 = winrm.exec().command("\"C:\\jdk\\jdk21.0.12_8\\bin\\java.exe\" -version 2>&1").execute();
            System.out.println("java -version: [" + r3.stdout().trim() + r3.stderr().trim() + "]");
        } else {
            System.out.println("!!! 解压超时未完成");
        }

        winrm.close();
        System.exit(0);
    }
}