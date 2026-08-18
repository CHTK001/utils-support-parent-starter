package com.chua.tui.support.dashboard;

import com.chua.common.support.network.ipc.annotations.IpcMethod;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;


/**
 * 系统监控数据提供者。
 * <p>
 * 通过 {@link IpcMethod} 注解暴露实时系统数据，
 * 供 {@link com.chua.tui.support.TuiDashboard} 中的组件消费。
 * 使用 Java 内置的 {@link OperatingSystemMXBean} 获取系统信息，
 * 无需额外依赖。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@IpcMethod("/system")
public class SystemMonitorService {

    /** 操作系统管理 Bean */
    /** OSbean */
    private final OperatingSystemMXBean osBean;

    /** JVM 运行时 */
    /** Runtime */
    private final Runtime runtime;

    /**
     * 构造系统监控服务。
     */
    public SystemMonitorService() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.runtime = Runtime.getRuntime();
    }

    /**
     * 获取 CPU 使用率。
     * <p>
     * 返回格式：{@code "45.2"}（百分比数值）。
     * 通过 OperatingSystemMXBean 获取系统 CPU 负载。
     * </p>
     *
     * @return CPU 使用率百分比字符串
     */
    @IpcMethod("/cpu")
    public String getCpuUsage() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                double cpuLoad = sunOsBean.getCpuLoad();
                if (cpuLoad >= 0) {
                    return String.format("%.1f", cpuLoad * 100);
                }
            }
            // 降级：返回 JVM 可用处理器信息
            return String.format("%.1f", osBean.getSystemLoadAverage());
        } catch (Exception e) {
            return "0.0";
        }
    }

    /**
     * 获取内存使用率。
     * <p>
     * 返回格式：{@code "使用率|已用GB|总量GB"}，如 {@code "45.2|8.2|16"}。
     * 通过 OperatingSystemMXBean 获取物理内存总量和已用量。
     * </p>
     *
     * @return 内存使用率字符串
     */
    @IpcMethod("/memory")
    public String getMemoryUsage() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                long totalMem = sunOsBean.getTotalMemorySize();
                long freeMem = sunOsBean.getFreeMemorySize();
                long usedMem = totalMem - freeMem;

                double usagePercent = (double) usedMem / totalMem * 100;
                double usedGb = usedMem / (1024.0 * 1024 * 1024);
                double totalGb = totalMem / (1024.0 * 1024 * 1024);

                return String.format("%.1f|%.1f|%.1f", usagePercent, usedGb, totalGb);
            }
            // 降级：使用 JVM 内存
            long totalMem = runtime.totalMemory();
            long freeMem = runtime.freeMemory();
            long usedMem = totalMem - freeMem;
            double usagePercent = (double) usedMem / totalMem * 100;
            double usedMb = usedMem / (1024.0 * 1024);
            double totalMb = totalMem / (1024.0 * 1024);

            return String.format("%.1f|%.1f|%.1f", usagePercent, usedMb, totalMb);
        } catch (Exception e) {
            return "0.0|0|16";
        }
    }

    /**
     * 获取磁盘使用率。
     * <p>
     * 返回格式：多行，每行 {@code "分区名|已用GB|总量GB|使用率"}。
     * 遍历所有根分区，获取每个分区的磁盘空间信息。
     * </p>
     *
     * @return 磁盘使用率字符串（多行）
     */
    @IpcMethod("/disk")
    public String getDiskUsage() {
        try {
            File[] roots = File.listRoots();
            if (roots == null || roots.length == 0) {
                return "N/A";
            }

            StringBuilder sb = new StringBuilder();
            for (File root : roots) {
                long total = root.getTotalSpace();
                long free = root.getFreeSpace();
                long used = total - free;

                if (total <= 0) {
                    continue;
                }

                double usagePercent = (double) used / total * 100;
                double usedGb = used / (1024.0 * 1024 * 1024);
                double totalGb = total / (1024.0 * 1024 * 1024);

                String mountPoint = root.getAbsolutePath().replace("\\", "");
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(String.format("%s|%.1f|%.1f|%.1f",
                        mountPoint, usedGb, totalGb, usagePercent));
            }
            return sb.toString();
        } catch (Exception e) {
            return "N/A";
        }
    }

    /**
     * 获取 htop 风格综合数据。
     * <p>
     * 第一行：系统概览 {@code "cpuUsage|memUsage|memUsed|memTotal"}。
     * 后续行：进程列表 {@code "processName|pid|cpu|mem"}。
     * 最多返回 10 个进程。
     * </p>
     *
     * @return htop 风格综合数据字符串（多行）
     */
    @IpcMethod("/htop")
    public String getHtopData() {
        try {
            StringBuilder sb = new StringBuilder();

            // 第一行：系统概览
            String memData = getMemoryUsage();
            String[] memParts = memData.split("\\|");

            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                double cpuLoad = sunOsBean.getCpuLoad();
                double cpuUsage = cpuLoad >= 0 ? cpuLoad * 100 : 0;

                sb.append(String.format("%.1f|%s",
                        cpuUsage, memData));
            } else {
                sb.append("0.0|").append(memData);
            }

            // 获取 JVM 信息作为进程示例
            long pid = ProcessHandle.current().pid();
            String jvmName = ManagementFactory.getRuntimeMXBean().getName();
            String processName = jvmName.contains("@")
                    ? jvmName.substring(0, jvmName.indexOf('@'))
                    : "java";

            double processCpu = 0.0;
            double processMem = 0.0;
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                processCpu = sunOsBean.getProcessCpuLoad() * 100;
            }
            long totalMem = runtime.totalMemory();
            long maxMem = runtime.maxMemory();
            if (maxMem > 0) {
                processMem = (double) totalMem / maxMem * 100;
            }

            sb.append("\n").append(String.format("%s|%d|%.1f|%.1f",
                    "java", pid, processCpu, processMem));

            return sb.toString();
        } catch (Exception e) {
            return "0.0|0.0|0|16\njava|1|0.0|0.0";
        }
    }
}
