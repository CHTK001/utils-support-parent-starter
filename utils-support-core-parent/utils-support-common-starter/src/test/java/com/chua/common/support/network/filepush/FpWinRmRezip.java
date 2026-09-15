package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 完整重新解压 jdk.zip（用 cmd 的 expand 替代 powershell 可能更稳）。
 */
public class FpWinRmRezip {
    public static void main(String[] args) throws Exception {
        WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== connected ===");

        // 删除已有不完整解压
        var r0 = winrm.exec().command("cmd.exe /c rmdir /s /q C:\\jdk\\jdk21.0.12_8").execute();
        System.out.println("删除旧目录: exit=" + r0.exitCode() + " [" + r0.stdout() + r0.stderr() + "]");

        // 用 PowerShell Expand-Archive 重新解压
        var r1 = winrm.exec().command("powershell -Command \"Expand-Archive -Path C:\\jdk\\jdk.zip -DestinationPath C:\\jdk -Force\"").execute();
        System.out.println("解压: exit=" + r1.exitCode() + " stdout=[" + r1.stdout() + "] stderr=[" + r1.stderr() + "]");

        // 验证 lib/jvm.cfg
        var r2 = winrm.exec().command("cmd.exe /c dir C:\\jdk\\jdk21.0.12_8\\lib\\jvm.cfg").execute();
        System.out.println("jvm.cfg: [" + r2.stdout() + "]");

        winrm.close();
        System.exit(0);
    }
}