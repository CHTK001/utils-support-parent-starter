package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 最终校验 D:\fp-sync 文件总数与总大小（用 PS1 文件方式，避免内联转义问题）。
 */
public class FpWinRmFinalCheck {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("=== 最终校验 D:\\fp-sync ===");

        // 写统计脚本到远程（cmd 里写 ps1 文件）
        wr.exec().command("cmd.exe /c (echo $s=(Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum); "
                + "echo \"COUNT=\"$s.Count; echo \"GB=\"([math]::Round($s.Sum/1GB,2)); "
                + "if ($s.Count -eq 128592) { echo STATUS=ALL_OK } else { echo STATUS=MISMATCH expected=128592 actual=$($s.Count) } "
                + ") > C:\\fp-push\\final-check.ps1 2>nul").execute();

        Thread.sleep(1000);
        var r = wr.exec().command("powershell -ExecutionPolicy Bypass -File C:\\fp-push\\final-check.ps1").execute();
        System.out.println(r.stdout().trim());
        if (!r.stderr().trim().isEmpty()) {
            System.out.println("stderr: " + r.stderr().trim());
        }

        // 补传 3 个失败文件已做，再统计那 3 个是否都在
        wr.exec().command("cmd.exe /c (echo $chk=Get-ChildItem 'D:\\fp-sync\\Program Files\\AutoClaw\\resources\\gateway\\openclaw\\node_modules\\@modelcontextprotocol\\sdk\\dist\\cjs\\server' -File | Where-Object { $_.Name -match '^(completable|express|mcp)\\.js$' } | ForEach-Object { $_.Name }; "
                + "echo $chk; "
                + ") > C:\\fp-push\\retry-check.ps1 2>nul").execute();
        var r2 = wr.exec().command("powershell -ExecutionPolicy Bypass -File C:\\fp-push\\retry-check.ps1").execute();
        System.out.println("补传 3 个文件验证: [" + r2.stdout().trim() + "]");

        wr.close();
        System.exit(0);
    }
}
