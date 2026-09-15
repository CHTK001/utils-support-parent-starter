package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;

import java.nio.file.*;
import java.util.Random;

/**
 * 完整远程实测：WinRM 在 172.16.9.194 启动 FilePushServer，
 * 本地高并发推送 500 文件 / ~1GB，远程校验。
 */
public class FpWinRmFullTest {

    static final String HOST = "172.16.9.194";
    static final String USER = "lenovo";
    static final String PASS = "123";
    static final int PORT = 9777;
    static final String JAVA = "C:\\jdk\\jdk21.0.12_8\\bin\\java.exe";
    static final String CLS = "C:\\fp-push\\classes";
    static final String TARGET = "C:\\fp-received";

    static WinRmExecClient winrm;

    public static void main(String[] args) throws Exception {
        System.out.println("=== FilePush 远程实测 (172.16.9.194) ===");

        // 1. WinRM 连接
        System.out.println("[1/6] WinRM 连接...");
        winrm = WinRmExecClient.builder()
                .host(HOST).port(5985)
                .username(USER).password(PASS)
                .authenticationScheme("NTLM")
                .build();
        winrm.connect();
        System.out.println("  连接成功");

        // 2. 确保 classes 已部署
        System.out.println("[2/6] 检查远程 classes...");
        var clschk = winrm.exec().command("cmd.exe /c dir /b C:\\fp-push\\classes\\com\\chua\\common\\support\\network\\filepush\\*.class").execute();
        System.out.println("  classes: " + clschk.stdout().trim());

        // 3. 远程启动 FilePushServer（后台）
        System.out.println("[3/6] 远程启动 FilePushServer (port " + PORT + ")...");
        stopServer();
        var startCmd = "cmd.exe /c \"start /b \"\" \"" + JAVA + "\" --enable-preview -cp " + CLS
                + " com.chua.common.support.network.filepush.FilePushServerMain " + PORT + " " + TARGET
                + " > C:\\fp-push\\server.log 2>&1\"";
        var r = winrm.exec().command(startCmd).execute();
        System.out.println("  启动命令 exit=" + r.exitCode() + " [" + r.stdout().trim() + "]");

        // 等待监听就绪
        Thread.sleep(3000);
        var log = winrm.exec().command("cmd.exe /c type C:\\fp-push\\server.log").execute();
        System.out.println("  服务端日志: " + log.stdout().trim());

        // 4. 本地生成测试目录 (~1GB)
        System.out.println("[4/6] 本地生成测试目录...");
        Path srcDir = Files.createTempDirectory("fp-remote-src");
        long totalBytes = generateFiles(srcDir);
        System.out.println("  生成 " + (totalBytes / 1024 / 1024) + " MB / " + countFiles(srcDir) + " 个文件");

        // 5. 本地高并发推送
        System.out.println("[5/6] 本地高并发推送...");
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
        System.out.println("=== 推送结果 ===");
        System.out.println("  成功: " + result.successCount() + " / 失败: " + result.failedCount());
        System.out.println("  耗时: " + elapsed + " ms");
        System.out.println("  吞吐: " + String.format("%.1f", result.throughputMbs()) + " MB/s");
        if (result.hasFailures()) {
            result.failures().forEach(f -> System.out.println("  FAILED: " + f.relativePath() + " -> " + f.error()));
        }

        // 6. 远程校验
        System.out.println();
        System.out.println("[6/6] 远程校验...");
        Thread.sleep(1000);
        var count = winrm.exec().command("cmd.exe /c dir /s /b " + TARGET + "\\*.bin 2>nul | find /c /v \"\" ").execute();
        var size = winrm.exec().command("cmd.exe /c powershell -Command \"(Get-ChildItem -Path " + TARGET
                + " -Recurse -File | Measure-Object -Property Length -Sum).Sum\"").execute();
        System.out.println("  远程文件数: " + count.stdout().trim());
        System.out.println("  远程总大小: " + (Long.parseLong(size.stdout().trim()) / 1024 / 1024) + " MB");
        System.out.println("  期望大小:   " + (totalBytes / 1024 / 1024) + " MB");

        // 校验通过 = 文件数匹配 & 大小匹配 & 无失败
        long remoteCount = Long.parseLong(count.stdout().trim());
        long remoteSize = Long.parseLong(size.stdout().trim());
        boolean ok = !result.hasFailures()
                && remoteCount == countFiles(srcDir)
                && remoteSize == totalBytes;

        System.out.println();
        System.out.println(ok ? "=== 远程同步 ALL PASSED ===" : "=== 远程同步 FAILED ===");

        // 清理
        stopServer();
        deleteRecursively(srcDir);
        winrm.close();
        System.exit(ok ? 0 : 1);
    }

    /** 停止远程 Server */
    static void stopServer() {
        try {
            winrm.exec().command("cmd.exe /c powershell -Command \"Get-CimInstance Win32_Process -Filter \\\"Name like '%java%'\\\" | Where-Object { $_.CommandLine -like '*FilePushServerMain*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }\"").execute();
            System.out.println("  已停止旧 Server");
        } catch (Exception e) {
            // 忽略
        }
    }

    /** 生成 500 个文件：0~10MB 随机，多级目录 */
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
            if (size > 0) rnd.nextBytes(data);
            Files.write(parent.resolve("file_" + i + ".bin"), data);
            total += size;
        }
        return total;
    }

    static long countFiles(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root).sorted(java.util.Comparator.reverseOrder())) {
            walk.forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}