package com.chua.winrm.support.client;

import com.chua.winrm.support.client.WinRmExecClient;
import java.nio.file.*;
import java.io.*;
import java.util.Random;

/**
 * 杩滅▼鐩綍鎺ㄩ€佸疄娴嬶細閫氳繃 WinRM 鍦?172.16.9.194 鍚姩 FilePushServer锛? * 鏈湴楂樺苟鍙戞帹閫佸ぇ閲忔枃浠跺苟鏍￠獙銆? */
public class FpRemoteTest {

    static final String HOST = "172.16.9.194";
    static final String USER = "lenov";
    static final String PASS = "123";
    static final int PORT = 9777;

    static WinRmExecClient winrm;
    static Process serverProc; // 鏈満杩愯鐨勮繙绋嬭浆鍙戣繘绋嬶紙闈炲繀闇€锛岀洿鎺?WinRM 璋冪敤鍗冲彲锛?
    public static void main(String[] args) throws Exception {
        System.out.println("=== FilePush 杩滅▼瀹炴祴 ===");

        // 1. WinRM 杩炴帴
        System.out.println("[1/6] WinRM 杩炴帴...");
        winrm = WinRmExecClient.builder()
                .host(HOST).port(5985)
                .username(USER).password(PASS)
                .authenticationScheme("Basic")
                .payloadEncryptionOff(true)
                .build();
        winrm.connect();
        System.out.println("  杩炴帴鎴愬姛");

        // 2. 妫€鏌ヨ繙绋?Java
        System.out.println("[2/6] 妫€鏌ヨ繙绋?Java...");
        var javaCheck = winrm.exec().command("java -version 2>&1").execute();
        System.out.println("  " + javaCheck.stdout().replace("\n", "\n  "));

        // 3. 鍒涘缓杩滅▼閮ㄧ讲鐩綍锛屾嫹璐?classes
        System.out.println("[3/6] 閮ㄧ讲 FilePush 鍒拌繙绋?..");
        String remoteBase = "C:\\fp-test";
        winrm.exec().command("Remove-Item -Recurse -Force \"" + remoteBase + "\" -ErrorAction SilentlyContinue").execute();
        winrm.exec().command("New-Item -ItemType Directory -Path \"" + remoteBase + "\" -Force | Out-Null").execute();

        // 鎵鹃」鐩?target/classes 璺緞
        String localClasses = "D:\\ch\\project\\utils-support-parent-starter\\utils-support-core-parent\\utils-support-common-starter\\target\\classes";
        deployDir(winrm, localClasses, remoteBase + "\\classes");

        // 鍒涘缓婧愭祴璇曠洰褰曪紙鏈湴澶х洰褰曪級
        System.out.println("[4/6] 鐢熸垚娴嬭瘯鐩綍锛垀1 GB锛?..");
        Path srcDir = Files.createTempDirectory("fp-remote-src");
        long totalBytes = generateTestFiles(srcDir);
        System.out.println("  鐢熸垚 " + totalBytes / 1024 / 1024 + " MB 娴嬭瘯鏁版嵁");

        // 5. 杩滅▼鍚姩 FilePushServer
        System.out.println("[5/6] 杩滅▼鍚姩 FilePushServer...");
        String targetDir = "C:\\fp-received";
        winrm.exec().command("New-Item -ItemType Directory -Path \"" + targetDir + "\" -Force | Out-Null").execute();

        // PowerShell 鍚庡彴鍚姩锛岃緭鍑洪噸瀹氬悜鍒版枃浠?        String startCmd = "Start-Process -FilePath java -ArgumentList @("
                + "'--enable-preview',"
                + "'-cp', '" + remoteBase + "\\classes',"
                + "'com.chua.common.support.network.filepush.FilePushServerMain'"
                + ") -RedirectStandardOutput '" + remoteBase + "\\out.log'"
                + " -RedirectStandardError '" + remoteBase + "\\err.log'"
                + " -WindowStyle Hidden -PassThru | Select-Object -ExpandProperty Id";
        var pidResult = winrm.exec().command(startCmd).execute();
        String pid = pidResult.stdout().trim();
        System.out.println("  Server PID=" + pid);

        // 绛夊緟鏈嶅姟绔惎鍔?        Thread.sleep(2000);

        // 妫€鏌ユ湇鍔＄鏄惁杩樻椿鐫€
        var alive = winrm.exec().command("(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue) -ne $null").execute();
        System.out.println("  Server alive=" + alive.stdout().trim());

        // 6. 鏈湴鎺ㄩ€?        System.out.println("[6/6] 鏈湴楂樺苟鍙戞帹閫?..");
        FilePushConfig cc = FilePushConfig.defaults();
        cc.setHost(HOST);
        cc.setPort(PORT);
        cc.setSourceDir(srcDir);
        cc.setClientFileParallelism(64);
        cc.setChunkSize(2 * 1024 * 1024); // 2MB 鍒嗙墖锛屽姞閫熷ぇ鏂囦欢

        long startMs = System.currentTimeMillis();
        FilePushClient.PushResult result;
        try (FilePushClient client = new FilePushClient(cc)) {
            result = client.push();
        }
        long elapsed = System.currentTimeMillis() - startMs;

        System.out.println();
        System.out.println("=== 鎺ㄩ€佺粨鏋?===");
        System.out.println("  鏂囦欢鏁? " + result.successCount() + " 鎴愬姛 / " + result.failedCount() + " 澶辫触");
        System.out.println("  瀛楄妭鏁? " + String.format("%.2f", result.throughputMbs() * result.elapsedMs() / 1000.0) + " MB");
        System.out.println("  鑰楁椂: " + elapsed + " ms");
        System.out.println("  鍚炲悙: " + String.format("%.1f", result.throughputMbs()) + " MB/s");
        if (result.hasFailures()) {
            System.out.println("  澶辫触鏂囦欢:");
            result.failures().forEach(f -> System.out.println("    " + f.relativePath() + " -> " + f.error()));
        }

        // 7. 杩滅▼鏍￠獙锛堢粺璁℃枃浠舵暟/瀛楄妭鏁帮級
        System.out.println();
        System.out.println("=== 杩滅▼鏍￠獙 ===");
        var countResult = winrm.exec().command(
                "(Get-ChildItem -Path \"" + targetDir + "\" -Recurse -File | Measure-Object).Count"
        ).execute();
        var sizeResult = winrm.exec().command(
                "Get-ChildItem -Path \"" + targetDir + "\" -Recurse -File | Measure-Object -Property Length -Sum | Select-Object -ExpandProperty Sum"
        ).execute();
        System.out.println("  杩滅▼鏂囦欢鏁? " + countResult.stdout().trim());
        System.out.println("  杩滅▼鎬诲ぇ灏? " + (Long.parseLong(sizeResult.stdout().trim()) / 1024 / 1024) + " MB");

        // 娓呯悊杩滅▼ Server
        winrm.exec().command("Stop-Process -Id " + pid + " -Force -ErrorAction SilentlyContinue").execute();
        System.out.println("  杩滅▼ Server 宸插仠姝?);

        // 娓呯悊鏈湴涓存椂鐩綍
        deleteRecursively(srcDir);

        boolean ok = result.failedCount() == 0 && !result.hasFailures();
        System.out.println();
        System.out.println(ok ? "=== ALL PASSED ===" : "=== SOME FAILED ===");
        System.exit(ok ? 0 : 1);
    }

    /** 鐢熸垚澶ч噺娴嬭瘯鏂囦欢锛?000 涓枃浠讹紝0~5MB锛屽灞傚瓙鐩綍 */
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
            if (size > 0) rnd.nextBytes(data);
            Files.write(root.resolve(dir + "/file_" + i + ".bin"), data);
            total += size;
        }
        return total;
    }

    /** 閫掑綊閮ㄧ讲鏈湴鐩綍鍒拌繙绋嬶紙WinRM + 鍘嬬缉浼犺緭锛?*/
    static void deployDir(WinRmExecClient winrm, String localDir, String remoteDir) throws Exception {
        Path dir = Path.of(localDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("鏈湴鐩綍涓嶅瓨鍦? " + localDir);
        }
        // 鍘嬬缉涓?zip
        Path zip = Files.createTempFile("fp-deploy-", ".zip");
        ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                "Compress-Archive -Path \"" + localDir + "\\*\" -DestinationPath \"" + zip.toAbsolutePath() + "\" -Force");
        pb.inheritIO().start().waitFor();

        // 涓婁紶 zip
        uploadFile(winrm, zip, remoteDir + ".zip");

        // 瑙ｅ帇
        winrm.exec().command("Expand-Archive -Path \"" + remoteDir + ".zip\" -DestinationPath \"" + remoteDir + "\" -Force").execute();
        winrm.exec().command("Remove-Item \"" + remoteDir + ".zip\" -Force -ErrorAction SilentlyContinue").execute();
        Files.deleteIfExists(zip);
        System.out.println("  閮ㄧ讲瀹屾垚: " + localDir + " -> " + remoteDir);
    }

    /** 涓婁紶鍗曚釜鏂囦欢鍒拌繙绋?*/
    static void uploadFile(WinRmExecClient winrm, Path localFile, String remotePath) throws Exception {
        // 閫氳繃 WinRM Shell 涓婁紶锛氬厛鍒涘缓鏂囦欢鍐呭锛岀敤 PowerShell 鐨?[IO.File]::WriteAllBytes
        byte[] data = Files.readAllBytes(localFile);
        // 缂栫爜涓?base64 浼犺緭
        String b64 = java.util.Base64.getEncoder().encodeToString(data);
        // 鍒嗗潡鍐欏叆锛圵inRM 鍛戒护鏈夊ぇ灏忛檺鍒讹級
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
                // 杩藉姞涓嶇洿鎺ユ敮鎸?base64 append锛屾敼鐢ㄥ垎鐗囧啓鍏ュ唴瀛樺悗涓€娆℃€т繚瀛?                // 瀵逛簬 FilePush 鍦烘櫙 classes 澶逛笉澶э紝zip 鍙兘鍑犲崄 MB锛屼竴娆″啓鍏ュ嵆鍙?            }
        }
    }

    /** 閫掑綊鍒犻櫎鐩綍 */
    static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root).sorted(java.util.Comparator.reverseOrder())) {
            walk.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
