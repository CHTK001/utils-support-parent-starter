package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 妫€鏌ヨ繙绋?Java 瀹夎浣嶇疆銆? */
public class FpWinRmJavaCheck {
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
        System.out.println("=== WinRM 杩炴帴鎴愬姛 ===");

        // 妫€鏌ュ父瑙?Java 瀹夎鐩綍
        String[] checks = {
                "Get-ChildItem 'C:\\Program Files\\Java' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName",
                "Get-ChildItem 'C:\\Program Files (x86)\\Java' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName",
                "Test-Path 'C:\\jdk'",
                "Test-Path 'C:\\Program Files\\jdk*'",
                "Get-Command javaw -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source",
                "Get-ChildItem Env:JAVA_HOME -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Value",
                "Get-ChildItem 'C:\\work' -ErrorAction SilentlyContinue | Select-Object -First 5 -ExpandProperty Name",
                "(Get-ChildItem 'C:\\' -Directory -ErrorAction SilentlyContinue).Name",
                "Get-CimInstance Win32_Processor | Select-Object -ExpandProperty NumberOfLogicalProcessors",
                "(Get-CimInstance Win32_OperatingSystem).TotalVisibleMemorySize"
        };

        for (int i = 0; i < checks.length; i++) {
            String cmd = checks[i];
            String result = winrm.exec().command(cmd).executeAndGetOutput();
            System.out.println("[" + (i+1) + "] " + cmd);
            System.out.println("    -> " + result.replaceAll("\n", "\n    -> "));
        }

        winrm.close();
        System.out.println("=== 妫€鏌ュ畬鎴?===");
        System.exit(0);
    }
}