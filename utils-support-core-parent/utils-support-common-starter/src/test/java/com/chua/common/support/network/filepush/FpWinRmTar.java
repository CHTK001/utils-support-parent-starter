package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 用 tar 后台解压 jdk.zip，不等结果。
 */
public class FpWinRmTar {
    public static void main(String[] args) throws Exception {
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== connected ===");

        // 用 start /b 调 tar 后台解压，立即返回
        var r1 = winrm.exec().command("cmd.exe /c \"start /b cmd.exe /c tar -xf C:\\jdk\\jdk.zip -C C:\\jdk && echo DONE > C:\\jdk\\done.txt\"").execute();
        System.out.println("后台解压命令已发送, exit=" + r1.exitCode() + " [" + r1.stdout() + "] [" + r1.stderr() + "]");

        // 等 5 秒后检查 jvm.cfg 是否出现
        Thread.sleep(5000);
        var r2 = winrm.exec().command("cmd.exe /c if exist C:\\jdk\\jdk21.0.12_8\\lib\\jvm.cfg echo JVR_OK else echo JVR_MISSING").execute();
        System.out.println("jvm.cfg 检查: [" + r2.stdout().trim() + "]");

        var r3 = winrm.exec().command("cmd.exe /c if exist C:\\jdk\\done.txt echo FULL_DONE else echo STILL_EXTRACTING").execute();
        System.out.println("解压完成检查: [" + r3.stdout().trim() + "]");

        winrm.close();
        System.exit(0);
    }
}