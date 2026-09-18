package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
/**
 * FpWinRmStatDirect类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmStatDirect {
    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        // 写一个干净的 ps1（用 base64 避免转义问题）
        String ps1 = "Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum | "
                + "ForEach-Object { $_.Count.ToString() + 'files ' + [math]::Round($_.Sum/1GB,2) + 'GB' }";
        String b64 = java.util.Base64.getEncoder().encodeToString(ps1.getBytes("UTF-8"));
        wr.exec().command("powershell -ExecutionPolicy Bypass -Command [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('" + b64 + "')) > C:\\fp-push\\stat-result.txt").execute();
        var r = wr.exec().command("cmd.exe /c type C:\\fp-push\\stat-result.txt").execute();
        System.out.println("远程统计: " + r.stdout().trim());
        wr.close();
        System.exit(0);
    }
}