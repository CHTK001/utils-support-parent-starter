﻿package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * FpWinRmDisk校验类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmDiskCheck {
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
        // 鐢?Get-CimInstance 鏇夸唬 wmic
        var r = winrm.exec().command("powershell -Command \"Get-CimInstance Win32_LogicalDisk | Select DeviceID, @{N='SizeGB';E={[math]::Round($_.Size/1GB,1)}}, @{N='FreeGB';E={[math]::Round($_.FreeSpace/1GB,1)}} | Format-Table -AutoSize\"").execute();
        System.out.println("=== Remote Disks ===");
        System.out.println(r.stdout());
        winrm.close();
        System.exit(0);
    }
}