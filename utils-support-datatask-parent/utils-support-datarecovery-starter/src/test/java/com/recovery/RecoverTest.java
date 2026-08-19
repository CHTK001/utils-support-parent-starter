package com.recovery;

import com.chua.datarecovery.support.DataRecovery;

/**
 * 恢复文件测试类
 * 用法: java RecoverTest [drive] [outputDir] [scanMode]
 *   drive: 如 F:\ 或 \\.\F:
 *   outputDir: 恢复文件输出目录 (默认 G:\recovered_files)
 * @author CH
 *   scanMode: 0=walkdir, 1=raw disk carve, 2=NTFS MFT (默认 1)
 */
public class RecoverTest {
    public static void main(String[] args) {
        String targetPath = "F:\\";
        String outputDir = "G:\\recovered_files";
        int scanMode = 1;

        if (args.length > 0) {
            targetPath = args[0];
        }
        if (args.length > 1) {
            outputDir = args[1];
        }
        if (args.length > 2) {
            scanMode = Integer.parseInt(args[2]);
        }

        System.out.println("=== 恢复文件测试 ===");
        System.out.println("目标路径: " + targetPath);
        System.out.println("输出目录: " + outputDir);
        System.out.println("扫描模式: " + getModeName(scanMode));

        try {
            DataRecovery recovery = DataRecovery.of(targetPath);
            long start = System.currentTimeMillis();
            DataRecovery.ScanResult result = recovery.scanAndRecover(scanMode, outputDir);
            long cost = System.currentTimeMillis() - start;

            System.out.println("扫描并恢复完成，耗时: " + cost + "ms");
            System.out.println("success: " + result.success);
            System.out.println("filesScanned: " + result.filesScanned);
            System.out.println("filesFound: " + result.filesFound);
            System.out.println("totalBytesWritten: " + formatSize(result.filesFound > 0 ? result.filesFound * 1024 * 1024 : 0));
            System.out.println("message: " + result.message);

            if (result.entries != null && result.entries.length > 0) {
                System.out.println("=== 恢复结果 (" + result.entries.length + " 个文件) ===");
                int limit = Math.min(result.entries.length, 20);
                for (int i = 0; i < limit; i++) {
                    DataRecovery.FileEntry entry = result.entries[i];
                    System.out.println("[" + (i + 1) + "] " + entry.name
                        + " | " + entry.path
                        + " | size=" + formatSize(entry.sizeBytes)
                        + " | score=" + entry.recoveryScore);
                }
                if (result.entries.length > 20) {
                    System.out.println("... 共 " + result.entries.length + " 个文件，仅显示前 20 个");
                }
            } else {
                System.out.println("未找到可恢复的文件。");
            }
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native library load failed: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("测试执行异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String getModeName(int mode) {
        switch (mode) {
            case 0: return "walkdir (现有文件)";
            case 1: return "raw-disk-carve (原始磁盘签名恢复)";
            case 2: return "ntfs-mft (NTFS MFT 删除记录)";
            default: return "unknown";
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }
}

