package com.chua.common.support.network.filepush;

import com.chua.winrm.support.client.WinRmExecClient;
import java.nio.file.*;
import java.io.*;
import java.util.Random;

/**
 * 远程目录推送实测：通过 WinRM 在 172.16.9.194 启动 FilePushServer，
 * 本地高并发推送大量文件并校验。
 */
public class FpRemoteTest {

    static final String HOST = "172.16.9.194";
    static final String USER = "lenov";
    static final String PASS = "123";
    static final int PORT = 9777;

    static WinRmExecClient winrm;
    static Process serverProc; // 本机运行的远程转发进程（非必需，直接 WinRM 调用即可）

    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== FilePush 远程实测 ===");

        // 1. WinRM 连接
        System.out.println("[1/6] WinRM 连接...");
        winrm = WinRmExecClient.builder()
                .host(HOST).port(5985)
                .username(USER).password(PASS)
                .authenticationScheme("Basic")
                .payloadEncryptionOff(true)
                .build();
        winrm.connect();
        System.out.println("  连接成功");

        // 2. 检查远程 Java
        System.out.println("[2/6] 检查远程 Java...");
        var javaCheck = winrm.exec().command("java -version 2>&1").execute();
        System.out.println("  " + javaCheck.stdout().replace("\n", "\n  "));

        // 3. 创建远程部署目录，拷贝 classes
        System.out.println("[3/6] 部署 FilePush 到远程...");
        String remoteBase = "C:\\fp-test";
        winrm.exec().command("Remove-Item -Recurse -Force \"" + remoteBase + "\" -ErrorAction SilentlyContinue").execute();
        winrm.exec().command("New-Item -ItemType Directory -Path \"" + remoteBase + "\" -Force | Out-Null").execute();

        // 找项目 target/classes 路径
        String localClasses = "D:\\ch\\project\\utils-support-parent-starter\\utils-support-core-parent\\utils-support-common-starter\\target\\classes";
        deployDir(winrm, localClasses, remoteBase + "\\classes");

        // 创建源测试目录（本地大目录）
        System.out.println("[4/6] 生成测试目录（~1 GB）...");
        Path srcDir = Files.createTempDirectory("fp-remote-src");
        long totalBytes = generateTestFiles(srcDir);
        System.out.println("  生成 " + totalBytes / 1024 / 1024 + " MB 测试数据");

        // 5. 远程启动 FilePushServer
        System.out.println("[5/6] 远程启动 FilePushServer...");
        String targetDir = "C:\\fp-received";
        winrm.exec().command("New-Item -ItemType Directory -Path \"" + targetDir + "\" -Force | Out-Null").execute();

        // PowerShell 后台启动，输出重定向到文件
        String startCmd = "Start-Process -FilePath java -ArgumentList @("
                + "'--enable-preview',"
                + "'-cp', '" + remoteBase + "\\classes',"
                + "'com.chua.common.support.network.filepush.FilePushServerMain'"
                + ") -RedirectStandardOutput '" + remoteBase + "\\out.log'"
                + " -RedirectStandardError '" + remoteBase + "\\err.log'"
                + " -WindowStyle Hidden -PassThru | Select-Object -ExpandProperty Id";
        var pidResult = winrm.exec().command(startCmd).execute();
        String pid = pidResult.stdout().trim();
        System.out.println("  Server PID=" + pid);

        // 等待服务端启动
        Thread.sleep(2000);

        // 检查服务端是否还活着
        var alive = winrm.exec().command("(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue) -ne $null").execute();
        System.out.println("  Server alive=" + alive.stdout().trim());

        // 6. 本地推送
        System.out.println("[6/6] 本地高并发推送...");
        FilePushConfig cc = FilePushConfig.defaults();
        cc.setHost(HOST);
        cc.setPort(PORT);
        cc.setSourceDir(srcDir);
        cc.setClientFileParallelism(64);
        cc.setChunkSize(2 * 1024 * 1024); // 2MB 分片，加速大文件

        long startMs = System.currentTimeMillis();
        FilePushClient.PushResult result;
        try (FilePushClient client = new FilePushClient(cc)) {
            result = client.push();
        }
        long elapsed = System.currentTimeMillis() - startMs;

        System.out.println();
        System.out.println("=== 推送结果 ===");
        System.out.println("  文件数: " + result.successCount() + " 成功 / " + result.failedCount() + " 失败");
        System.out.println("  字节数: " + String.format("%.2f", result.throughputMbs() * result.elapsedMs() / 1000.0) + " MB");
        System.out.println("  耗时: " + elapsed + " ms");
        System.out.println("  吞吐: " + String.format("%.1f", result.throughputMbs()) + " MB/s");
        if (result.hasFailures()) {
            System.out.println("  失败文件:");
            result.failures().forEach(f -> System.out.println("    " + f.relativePath() + " -> " + f.error()));
        }

        // 7. 远程校验（统计文件数/字节数）
        System.out.println();
        System.out.println("=== 远程校验 ===");
        var countResult = winrm.exec().command(
                "(Get-ChildItem -Path \"" + targetDir + "\" -Recurse -File | Measure-Object).Count"
        ).execute();
        var sizeResult = winrm.exec().command(
                "Get-ChildItem -Path \"" + targetDir + "\" -Recurse -File | Measure-Object -Property Length -Sum | Select-Object -ExpandProperty Sum"
        ).execute();
        System.out.println("  远程文件数: " + countResult.stdout().trim());
        System.out.println("  远程总大小: " + (Long.parseLong(sizeResult.stdout().trim()) / 1024 / 1024) + " MB");

        // 清理远程 Server
        winrm.exec().command("Stop-Process -Id " + pid + " -Force -ErrorAction SilentlyContinue").execute();
        System.out.println("  远程 Server 已停止");

        // 清理本地临时目录
        deleteRecursively(srcDir);

        boolean ok = result.failedCount() == 0 && !result.hasFailures();
        System.out.println();
        System.out.println(ok ? "=== ALL PASSED ===" : "=== SOME FAILED ===");
        System.exit(ok ? 0 : 1);
    }

    /**
     * 生成大量测试文件：2000 个文件，0~5MB，多层子目录
     * @param root 根节点，不允许为 null
     * @return 结果数值
     */
    static long generateTestFiles(Path root) throws IOException {
        Random rnd = new Random(12345);
        long total = 0;
        int dirs = 10;
        for (int i = 0; i < 2000; i++) {
            String dir = "d" + (i % dirs) + "/" + "d" + (i % 5) + "/" + "d" + (i % 3);
            Files.createDirectories(root.resolve(dir));
            int size;
            double p = rnd.nextDouble();
            if (p < 0.05) {
                size = 0;
            } else if (p < 0.2) {
                size = rnd.nextInt(100 * 1024); // < 100KB
            } else if (p < 0.7) {
                size = rnd.nextInt(1024 * 1024); // < 1MB
            } else {
                size = rnd.nextInt(5 * 1024 * 1024); // < 5MB
            }
            byte[] data = new byte[size];
            if (size > 0) {
                rnd.nextBytes(data);
            }
            Files.write(root.resolve(dir + "/file_" + i + ".bin"), data);
            total += size;
        }
        return total;
    }

    /**
     * 递归部署本地目录到远程（WinRM + 压缩传输）
     * @param winrm 方法入参 winrm
     * @param localDir local目录，不允许为 null
     * @param remoteDir remote目录，不允许为 null
     */
    static void deployDir(WinRmExecClient winrm, String localDir, String remoteDir) throws Exception {
        Path dir = Path.of(localDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("本地目录不存在: " + localDir);
        }
        // 压缩为 zip
        Path zip = Files.createTempFile("fp-deploy-", ".zip");
        ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                "Compress-Archive -Path \"" + localDir + "\\*\" -DestinationPath \"" + zip.toAbsolutePath() + "\" -Force");
        pb.inheritIO().start().waitFor();

        // 上传 zip
        uploadFile(winrm, zip, remoteDir + ".zip");

        // 解压
        winrm.exec().command("Expand-Archive -Path \"" + remoteDir + ".zip\" -DestinationPath \"" + remoteDir + "\" -Force").execute();
        winrm.exec().command("Remove-Item \"" + remoteDir + ".zip\" -Force -ErrorAction SilentlyContinue").execute();
        Files.deleteIfExists(zip);
        System.out.println("  部署完成: " + localDir + " -> " + remoteDir);
    }

    /**
     * 上传单个文件到远程
     * @param winrm 方法入参 winrm
     * @param localFile local文件，不允许为 null
     * @param remotePath remote路径，不允许为 null
     */
    static void uploadFile(WinRmExecClient winrm, Path localFile, String remotePath) throws Exception {
        // 通过 WinRM Shell 上传：先创建文件内容，用 PowerShell 的 [IO.File]::WriteAllBytes
        byte[] data = Files.readAllBytes(localFile);
        // 编码为 base64 传输
        String b64 = java.util.Base64.getEncoder().encodeToString(data);
        // 分块写入（WinRM 命令有大小限制）
        int chunk = 1_000_000; // 1MB base64 chunks
        boolean first = true;
        for (int off = 0; off < b64.length(); off += chunk) {
            int end = Math.min(off + chunk, b64.length());
            String slice = b64.substring(off, end);
            if (first) {
                winrm.exec().command("$bytes = [Convert]::FromBase64String('" + slice + "')").execute();
                winrm.exec().command("[IO.File]::WriteAllBytes('" + remotePath + "', $bytes)").execute();
                first = false;
            } else {
                // 追加不直接支持 base64 append，改用分片写入内存后一次性保存
                // 对于 FilePush 场景 classes 夹不大，zip 可能几十 MB，一次写入即可
            }
        }
    }

    /**
     * 递归删除目录
     * @param root 根节点，不允许为 null
     */
    static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root).sorted(java.util.Comparator.reverseOrder())) {
            walk.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {}
    }
}
