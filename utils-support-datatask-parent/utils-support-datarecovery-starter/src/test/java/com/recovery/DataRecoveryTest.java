package com.recovery;

import com.chua.datarecovery.support.DataRecovery;
import com.chua.common.support.utils.NativeUtils;

/**
 * DataRecovery 扫描测试类
 *
 * Maven 依赖引用:
 * - com.chua:utils-support-common-starter (提供 NativeUtils)
 * @author CH
 * - com.fasterxml.jackson.core:jackson-databind (JSON 解析)
 */
public class DataRecoveryTest {
    public static void main(String[] args) {
        String targetPath = "F:\\";
        if (args.length > 0) {
            targetPath = args[0];
        }

        System.out.println("=== DataRecovery 扫描测试 ===");
        System.out.println("目标路径: " + targetPath);
        System.out.println("加载 native 库: data_recovery_ffi");

        try {
            DataRecovery recovery = DataRecovery.of(targetPath);

            // scanMode: 0=walkdir 现有文件, 1=raw disk carve, 2=NTFS MFT 已删除文件
            int scanMode = 1;
            if (args.length > 1) {
                scanMode = Integer.parseInt(args[1]);
            }
            String modeName = scanMode == 0 ? "walkdir" : (scanMode == 1 ? "raw-disk-carve" : "ntfs-mft");
            System.out.println("扫描模式: " + modeName);
            System.out.println("开始扫描...");
            long start = System.currentTimeMillis();

            DataRecovery.ScanResult result;
            if (scanMode == 1) {
                String outputDir = "G:\\recovered_files";
                System.out.println("扫描并自动恢复模式，输出目录: " + outputDir);
                result = recovery.scanAndRecover(scanMode, outputDir);
            } else {
                result = recovery.scan(scanMode);
            }
            long cost = System.currentTimeMillis() - start;

            System.out.println("扫描完成，耗时: " + cost + "ms");
            System.out.println("success: " + result.success);
            System.out.println("filesScanned: " + result.filesScanned);
            System.out.println("filesFound: " + result.filesFound);
            System.out.println("message: " + result.message);
            System.out.println("entries length: " + (result.entries != null ? result.entries.length : "null"));

            if (result.entries != null && result.entries.length > 0) {
                System.out.println("=== 前 20 个匹配项 ===");
                int limit = Math.min(result.entries.length, 20);
                for (int i = 0; i < limit; i++) {
                    DataRecovery.FileEntry entry = result.entries[i];
                    System.out.println("[" + (i + 1) + "] " + entry.name + " | " + entry.path + " | size=" + entry.sizeBytes);
                }
                if (result.entries.length > 20) {
                    System.out.println("... 共 " + result.entries.length + " 条，仅显示前 20 条");
                }
            } else {
                System.out.println("No entries found. Check if drive has deleted files or if NTFS MFT parsing works correctly.");
            }
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native library load failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("测试执行异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
