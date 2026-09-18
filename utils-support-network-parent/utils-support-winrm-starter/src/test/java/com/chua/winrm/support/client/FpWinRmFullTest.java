package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;

import java.nio.file.*;
import java.util.Random;

/**
 * 瀹屾暣杩滅▼瀹炴祴锛歐inRM 鍦?172.16.9.194 鍚姩 FilePushServer锛? * 鏈湴楂樺苟鍙戞帹閫?500 鏂囦欢 / ~1GB锛岃繙绋嬫牎楠屻€? */
public class FpWinRmFullTest {

    static final String HOST = "172.16.9.194";
    static final String USER = "lenovo";
    static final String PASS = "123";
    static final int PORT = 9777;
    static final String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
    static final String CLS = "C:\\fp-push\\classes";
    static final String TARGET = "C:\\fp-received";

    static WinRmExecClient winrm;

    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== FilePush 杩滅▼瀹炴祴 (172.16.9.194) ===");

        // 1. WinRM 杩炴帴
        System.out.println("[1/6] WinRM 杩炴帴...");
        winrm = WinRmExecClient.builder()
                .host(HOST).port(5985)
                .username(USER).password(PASS)
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("  杩炴帴鎴愬姛");

        // 2. 纭繚 classes 宸查儴缃?        System.out.println("[2/6] 妫€鏌ヨ繙绋?classes...");
        var clschk = winrm.exec().command("cmd.exe /c dir /b C:\\fp-push\\classes\\com\\chua\\common\\support\\network\\filepush\\*.class").execute();
        System.out.println("  classes: " + clschk.stdout().trim());

        // 3. 杩滅▼鍚姩 FilePushServer锛堝悗鍙帮級
        System.out.println("[3/6] 杩滅▼鍚姩 FilePushServer (port " + PORT + ")...");
        stopServer();
        var startCmd = "cmd.exe /c \"start /b \"\" \"" + JAVA + "\" --enable-preview -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain " + PORT + " " + TARGET
                + " > C:\\fp-push\\server.log 2>&1\"";
        var r = winrm.exec().command(startCmd).execute();
        System.out.println("  鍚姩鍛戒护 exit=" + r.exitCode() + " [" + r.stdout().trim() + "]");

        // 绛夊緟鐩戝惉灏辩华
        Thread.sleep(3000);
        var log = winrm.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("  鏈嶅姟绔棩蹇? " + log.stdout().trim());

        // 4. 鏈湴鐢熸垚娴嬭瘯鐩綍 (~1GB)
        System.out.println("[4/6] 鏈湴鐢熸垚娴嬭瘯鐩綍...");
        Path srcDir = Files.createTempDirectory("fp-remote-src");
        long totalBytes = generateFiles(srcDir);
        System.out.println("  鐢熸垚 " + (totalBytes / 1024 / 1024) + " MB / " + countFiles(srcDir) + " 涓枃浠?);

        // 5. 鏈湴楂樺苟鍙戞帹閫?        System.out.println("[5/6] 鏈湴楂樺苟鍙戞帹閫?..");
        FilePushConfig cc = FilePushConfig.defaults();
        cc.setHost(HOST);
        cc.setPort(PORT);
        cc.setSourceDir(srcDir);
        cc.setClientFileParallelism(128);
        cc.setChunkSize(4 * 1024 * 1024);
        cc.setConnectTimeoutMs(15000);
        cc.setReadTimeoutMs(120000);

        long start = System.currentTimeMillis();
        FilePushClient.PushResult result;
        try (FilePushClient client = new FilePushClient(cc)) {
            result = client.push();
        }
        long elapsed = System.currentTimeMillis() - start;

        System.out.println();
        System.out.println("=== 鎺ㄩ€佺粨鏋?===");
        System.out.println("  鎴愬姛: " + result.successCount() + " / 澶辫触: " + result.failedCount());
        System.out.println("  鑰楁椂: " + elapsed + " ms");
        System.out.println("  鍚炲悙: " + String.format("%.1f", result.throughputMbs()) + " MB/s");
        if (result.hasFailures()) {
            result.failures().forEach(f -> System.out.println("  FAILED: " + f.relativePath() + " -> " + f.error()));
        }

        // 6. 杩滅▼鏍￠獙
        System.out.println();
        System.out.println("[6/6] 杩滅▼鏍￠獙...");
        Thread.sleep(1000);
        var count = winrm.exec().command("cmd.exe /c dir /s /b " + TARGET + "\\*.bin 2>nul | find /c /v \"\" ").execute();
        var size = winrm.exec().command("cmd.exe /c powershell -Command \"(Get-ChildItem -Path " + TARGET
                + " -Recurse -File | Measure-Object -Property Length -Sum).Sum\"").execute();
        System.out.println("  杩滅▼鏂囦欢鏁? " + count.stdout().trim());
        System.out.println("  杩滅▼鎬诲ぇ灏? " + (Long.parseLong(size.stdout().trim()) / 1024 / 1024) + " MB");
        System.out.println("  鏈熸湜澶у皬:   " + (totalBytes / 1024 / 1024) + " MB");

        // 鏍￠獙閫氳繃 = 鏂囦欢鏁板尮閰?& 澶у皬鍖归厤 & 鏃犲け璐?        long remoteCount = Long.parseLong(count.stdout().trim());
        long remoteSize = Long.parseLong(size.stdout().trim());
        boolean ok = !result.hasFailures()
                && remoteCount == countFiles(srcDir)
                && remoteSize == totalBytes;

        System.out.println();
        System.out.println(ok ? "=== 杩滅▼鍚屾 ALL PASSED ===" : "=== 杩滅▼鍚屾 FAILED ===");

        // 娓呯悊
        stopServer();
        deleteRecursively(srcDir);
        winrm.close();
        System.exit(ok ? 0 : 1);
    }

    /** 鍋滄杩滅▼ Server */
    static void stopServer() {
        try {
            winrm.exec().command("cmd.exe /c powershell -Command \"Get-CimInstance Win32_Process -Filter \\\"Name like '%java%'\\\" | Where-Object { $_.CommandLine -like '*FilePushServerMain*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }\"").execute();
            System.out.println("  宸插仠姝㈡棫 Server");
        } catch (Exception e) {
            // 蹇界暐
        }
    }

    /**
     * 鐢熸垚 500 涓枃浠讹細0~10MB 闅忔満锛屽绾х洰褰?
     * @param root 根节点，不允许为 null
     * @return 结果数值
     */
    static long generateFiles(Path root) throws Exception {
        Random rnd = new Random(7);
        long total = 0;
        for (int i = 0; i < 500; i++) {
            String dir = "d" + (i % 10) + "/" + "d" + (i % 5) + "/" + "d" + (i % 2);
            Path parent = root.resolve(dir);
            Files.createDirectories(parent);
            int size;
            double p = rnd.nextDouble();
            if (p < 0.05) {
                size = 0;
            } else if (p < 0.3) {
                size = rnd.nextInt(200 * 1024);
            } else if (p < 0.7) {
                size = rnd.nextInt(2 * 1024 * 1024);
            } else {
                size = rnd.nextInt(10 * 1024 * 1024);
            }
            byte[] data = new byte[size];
            if (size > 0) {
                rnd.nextBytes(data);
            }
            Files.write(parent.resolve("file_" + i + ".bin"), data);
            total += size;
        }
        return total;
    }

    /**
     * 数量Files。
     *
     * @param root 根节点，不允许为 null
     * @return 结果数值
     * @throws Exception 当执行过程不满足前置条件时
     */
    static long countFiles(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    /**
     * 删除Recursively。
     *
     * @param root 根节点，不允许为 null
     */
    static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root).sorted(java.util.Comparator.reverseOrder())) {
            walk.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 删除失败可忽略
                }
            });
        } catch (Exception ignored) {}
    }
}