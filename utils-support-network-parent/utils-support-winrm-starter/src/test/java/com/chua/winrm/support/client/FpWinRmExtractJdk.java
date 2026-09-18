package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

/**
 * 鐢?start 鍚庡彴瀹屾暣瑙ｅ帇 jdk.zip锛岃疆璇㈠畬鎴愮姸鎬併€? */
public class FpWinRmExtractJdk {
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
        System.out.println("=== connected ===");

        // 1. 鍒犻櫎涓嶅畬鏁寸洰褰?        var r0 = winrm.exec().command("cmd.exe /c rmdir /s /q C:\\jdk\\jdk21.0.12_8 2>nul").execute();
        System.out.println("鍒犻櫎鏃х洰褰?exit=" + r0.exitCode());

        // 2. 鍚庡彴瑙ｅ帇锛坰tart /b 绔嬪嵆杩斿洖锛?        var r1 = winrm.exec().command("cmd.exe /c \"start /b cmd.exe /c tar -xf C:\\jdk\\jdk.zip -C C:\\jdk && echo DONE> C:\\jdk\\done.txt\" >nul 2>nul").execute();
        System.out.println("鍚庡彴瑙ｅ帇宸插惎鍔?exit=" + r1.exitCode() + " [" + r1.stdout() + "] [" + r1.stderr() + "]");

        // 3. 杞瀹屾垚 (鏈€澶?60s)
        boolean done = false;
        for (int i = 0; i < 12; i++) {
            Thread.sleep(5000);
            var chk = winrm.exec().command("cmd.exe /c if exist C:\\jdk\\done.txt (echo DONE) else (echo WAIT)").execute();
            String s = chk.stdout().trim();
            System.out.println("  杞 " + (i+1) + ": " + s);
            if ("DONE".equals(s)) {
                done = true;
                break;
            }
        }

        // 4. 楠岃瘉 jvm.cfg 鍜?java -version
        if (done) {
            var r2 = winrm.exec().command("cmd.exe /c dir /b C:\\jdk\\jdk21.0.12_8\\lib\\jvm.cfg").execute();
            System.out.println("jvm.cfg: [" + r2.stdout().trim() + "]");
            var r3 = winrm.exec().command("\"C:\\jdk\\jdk21.0.12_8\\bin\\java.exe\" -version 2>&1").execute();
            System.out.println("java -version: [" + r3.stdout().trim() + r3.stderr().trim() + "]");
        } else {
            System.out.println("!!! 瑙ｅ帇瓒呮椂鏈畬鎴?);
        }

        winrm.close();
        System.exit(0);
    }
}