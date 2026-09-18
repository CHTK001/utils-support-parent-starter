package com.chua.winrm.support.client;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmStatDirect {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        // 鍐欎竴涓共鍑€鐨?ps1锛堢敤 base64 閬垮厤杞箟闂锛?        String ps1 = "Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum | "
                + "ForEach-Object { $_.Count.ToString() + 'files ' + [math]::Round($_.Sum/1GB,2) + 'GB' }";
        String b64 = java.util.Base64.getEncoder().encodeToString(ps1.getBytes("UTF-8"));
        wr.exec().command("powershell -ExecutionPolicy Bypass -Command [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('" + b64 + "')) > C:\\fp-push\\stat-result.txt").execute();
        var r = wr.exec().command("cmd.exe /c type C:\\fp-push\\stat-result.txt").execute();
        System.out.println("杩滅▼缁熻: " + r.stdout().trim());
        wr.close();
        System.exit(0);
    }
}