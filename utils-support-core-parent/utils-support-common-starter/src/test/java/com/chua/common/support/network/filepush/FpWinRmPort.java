package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmPort {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        var j = wr.exec().command("cmd.exe /c tasklist /fi \"imagename eq java.exe\" /fo csv /nh").execute();
        System.out.println("java processes: " + j.stdout().trim());
        var s1 = wr.exec().command("cmd.exe /c netstat -ano | findstr :9777").execute();
        System.out.println("9777: " + s1.stdout().trim());
        var s2 = wr.exec().command("cmd.exe /c netstat -ano | findstr :9999").execute();
        System.out.println("9999: " + s2.stdout().trim());
        // 直接连接测试
        var test = wr.exec().command("cmd.exe /c powershell -Command \"try { (New-Object Net.Sockets.TcpClient).Connect(\\\"127.0.0.1\\\", 9777); \\\"9777 LOCAL OK\\\" } catch { \\\"9777 LOCAL FAIL: \\\" + $_.Exception.Message }\"").execute();
        System.out.println("local 9777 test: " + test.stdout().trim());
        wr.close();
        System.exit(0);
    }
}