package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmSpeedTest {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        var r = wr.exec().command("cmd.exe /c powershell -Command \"Get-CimInstance Win32_NetworkAdapterConfiguration | Where-Object { $_.IPAddress -ne $null } | Select-Object -Property Description, Speed, LinkSpeed | Format-Table\"").execute();
        System.out.println("网卡速度: " + r.stdout().trim());
        wr.close();
        System.exit(0);
    }
}