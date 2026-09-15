package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * WinRM 连接与远程环境探测。
 */
public class FpWinRmProbe {
    public static void main(String[] args) throws Exception {
WinRmExecClient winrm = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("=== WinRM 连接成功 ===");

        // Java
        var java = winrm.exec().command("java -version 2>&1").execute();
        System.out.println("--- java -version ---");
        System.out.println(java.stdout());
        System.out.println(java.stderr());

        // 系统信息
        var os = winrm.exec().command("$env:COMPUTERNAME + ' | ' + $env:PROCESSOR_ARCHITECTURE + ' | ' + (Get-CimInstance Win32_OperatingSystem).Caption").execute();
        System.out.println("--- 系统 ---");
        System.out.println(os.stdout());

        // 本机 IP
        var ip = winrm.exec().command("(Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.InterfaceAlias -notmatch 'Loopback'}).IPAddress -join ','").execute();
        System.out.println("--- IP ---");
        System.out.println(ip.stdout());

        // 测试目标目录创建
        winrm.exec().command("New-Item -ItemType Directory -Path 'C:\\fp-test' -Force | Out-Null").execute();
        var exists = winrm.exec().command("Test-Path 'C:\\fp-test'").execute();
        System.out.println("--- C:\\fp-test 创建 ---");
        System.out.println(exists.stdout());

        winrm.close();
        System.out.println("=== 探测完成 ===");
    }
}
