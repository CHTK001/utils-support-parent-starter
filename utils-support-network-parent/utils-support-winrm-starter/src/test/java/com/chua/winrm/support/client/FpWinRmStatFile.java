package com.chua.winrm.support.client;
import com.chua.winrm.support.client.WinRmExecClient;
/**
 * FpWinRmStat文件类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmStatFile {
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
        // 鍐欑粺璁¤剼鏈紙缁撴灉杈撳嚭鍒版枃浠讹級
        wr.exec().command("cmd.exe /c echo $s=Get-ChildItem D:\\fp-sync -Recurse -File | Measure-Object -Property Length -Sum > C:\\fp-push\\c.ps1").execute();
        wr.exec().command("cmd.exe /c echo Write-Output \"files=`$$s.Count bytes=`$$s.Sum\" >> C:\\fp-push\\c.ps1").execute();
        // 鎵ц鑴氭湰骞跺啓缁撴灉
        wr.exec().command("powershell -ExecutionPolicy Bypass -File C:\\fp-push\\c.ps1 > C:\\fp-push\\stat-result.txt 2>&1").execute();
        System.out.println("done");
        wr.close();
        System.exit(0);
    }
}