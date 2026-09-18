package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 杩滅▼鎵ц鏂囦欢缁熻锛氬啓 PS1 鑴氭湰鍒?D 鐩橈紝PowerShell 鎵ц锛岀粨鏋滃啓鏂囨湰鏂囦欢渚?SMB 璇诲彇銆? */
public class FpWinRmStatPs1 {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        System.out.println("=== 鎵ц杩滅▼缁熻 ===");

        // 鍐?PS1 鑴氭湰锛圥owerShell 鐢?Out-File 鍐?PS1锛?        String ps1Content = "(Get-ChildItem 'D:\\fp-sync' -Recurse -File | Measure-Object -Property Length -Sum)"
                + " | Out-File 'C:\\fp-push\\stat-out.txt'";
        String ps1File = "C:\\fp-push\\stat.ps1";

        // 鐩存帴鐢?PowerShell 鍐欒剼鏈枃浠讹紙閬垮厤 cmd 杞箟 $ 闂锛?        String writeCmd = "powershell -Command \"& { "
                + "'$s = Get-ChildItem D:\\fp-sync -Recurse -File | Measure-Object -Property Length -Sum' | "
                + "Out-File '" + ps1File + "' }\"";
        wr.exec().command(writeCmd).execute();
        System.out.println("[1] stat.ps1 宸插啓鍏?);

        // 鎵ц缁熻鑴氭湰锛堝彲鑳借緝鎱紝12.8涓囨枃浠讹級
        var exec = wr.exec().command("powershell -ExecutionPolicy Bypass -File " + ps1File).execute();
        System.out.println("[2] 缁熻瀹屾垚");

        wr.close();
        System.exit(0);
    }
}
