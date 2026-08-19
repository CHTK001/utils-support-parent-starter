package com.recovery;

import com.chua.datarecovery.support.DataRecovery;

/**
 * 扫描删除文件测试类
 * 用法: java ScanTest [drive] [scanMode]
 *   drive: 如 F:\ 或 \\.\F:
 * @author CH
 *   scanMode: 0=walkdir, 1=raw disk carve, 2=NTFS MFT (默认 1)
 */
public class ScanTest {
    /** Main */
    public static void main(String[] args) {
        String targetPath = "F:\\";
        if (args.length > 0) {
            targetPath = args[0];
        }
        int scanMode = 1;
        if (args.length > 1) {
            scanMode = Integer.parseInt(args[1]);
        }

        System.out.println("=== 扫描删除文件测试 ===");
        System.out.println("目标路径: " + targetPath);
        System.out.println("扫描模式: " + getModeName(scanMode));

        try {
            DataRecovery recovery = DataRecovery.of(targetPath);
            long start = System.currentTimeMillis();
            DataRecovery.ScanResult result = recovery.scan(scanMode);
            long cost = System.currentTimeMillis() - start;

            System.out.println("扫描完成，耗时: " + cost + "ms");
            System.out.println("success: " + result.success);
            System.out.println("filesScanned: " + result.filesScanned);
            System.out.println("filesFound: " + result.filesFound);
            System.out.println("message: " + result.message);

            if (result.entries != null && result.entries.length > 0) {
                System.out.println("=== 扫描结果 (" + result.entries.length + " 条) ===");
                int limit = Math.min(result.entries.length, 50);
                for (int i = 0; i < limit; i++) {
                    DataRecovery.FileEntry entry = result.entries[i];
                    System.out.println("[" + (i + 1) + "] " + entry.name
                        + " | " + entry.path
                        + " | size=" + formatSize(entry.sizeBytes)
                        + " | score=" + entry.recoveryScore);
                }
                if (result.entries.length > 50) {
                    System.out.println("... 共 " + result.entries.length + " 条，仅显示前 50 条");
                }
            } else {
                System.out.println("未找到删除的文件。");
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

    /** 获取ModeName */
    private static String getModeName(int mode) {
        switch (mode) {
            case 0: return "walkdir (现有文件)";
            case 1: return "raw-disk-carve (原始磁盘签名)";
            case 2: return "ntfs-mft (NTFS MFT 删除记录)";
            default: return "unknown";
        }
    }

    /** 格式化获取大小 */
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

