package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鏈€缁堟牎楠?D:\fp-sync 鏂囦欢鎬绘暟涓庢€诲ぇ灏忥紙鐢?PS1 鏂囦欢鏂瑰紡锛岄伩鍏嶅唴鑱旇浆涔夐棶棰橈級銆? */
public class FpWinRmFinalCheck {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("=== 鏈€缁堟牎楠?D:\\fp-sync ===");

        // 鍐欑粺璁¤剼鏈埌杩滅▼锛坈md 閲屽啓 ps1 鏂囦欢锛?        wr.exec().command("cmd.exe /c (echo $s=(Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum); "
                + "echo \"COUNT=\"$s.Count; echo \"GB=\"([math]::Round($s.Sum/1GB,2)); "
                + "if ($s.Count -eq 128592) { echo STATUS=ALL_OK } else { echo STATUS=MISMATCH expected=128592 actual=$($s.Count) } "
                + ") > C:\\fp-push\\final-check.ps1 2>nul").execute();

        Thread.sleep(1000);
        var r = wr.exec().command("powershell -ExecutionPolicy Bypass -File C:\\fp-push\\final-check.ps1").execute();
        System.out.println(r.stdout().trim());
        if (!r.stderr().trim().isEmpty()) {
            System.out.println("stderr: " + r.stderr().trim());
        }

        // 琛ヤ紶 3 涓け璐ユ枃浠跺凡鍋氾紝鍐嶇粺璁￠偅 3 涓槸鍚﹂兘鍦?        wr.exec().command("cmd.exe /c (echo $chk=Get-ChildItem 'D:\\fp-sync\\Program Files\\AutoClaw\\resources\\gateway\\openclaw\\node_modules\\@modelcontextprotocol\\sdk\\dist\\cjs\\server' -File | Where-Object { $_.Name -match '^(completable|express|mcp)\\.js$' } | ForEach-Object { $_.Name }; "
                + "echo $chk; "
                + ") > C:\\fp-push\\retry-check.ps1 2>nul").execute();
        var r2 = wr.exec().command("powershell -ExecutionPolicy Bypass -File C:\\fp-push\\retry-check.ps1").execute();
        System.out.println("琛ヤ紶 3 涓枃浠堕獙璇? [" + r2.stdout().trim() + "]");

        wr.close();
        System.exit(0);
    }
}
