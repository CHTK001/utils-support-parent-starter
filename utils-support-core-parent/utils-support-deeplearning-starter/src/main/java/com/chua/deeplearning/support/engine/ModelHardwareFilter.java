package com.chua.deeplearning.support.engine;

import com.chua.deeplearning.support.model.HardwareConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 模型硬件过滤器：按本机硬件（GPU 显存 / 系统内存）判断注册模型是否可运行。
 *
 * <p>模型注册时可通过 {@link HardwareConfig} 声明运行要求（设备类型 + 最低内存/显存）。
 * 本过滤器在模型目录聚合、模型选择等入口调用，把当前机器跑不动的模型剔除出可选范围，
 * 避免用户选中后运行即 OOM/崩溃。判定口径：</p>
 * <ul>
 *   <li>无 {@link HardwareConfig} → 放行（与历史行为一致）；</li>
 *   <li>minVramMb ≤ 0 → 放行（无容量要求）；</li>
 *   <li>device=gpu → 本机必须有可用 NVIDIA GPU（{@link DeviceSelector#isGpuUsable()}），
 *       且总显存 ≥ minVramMb（显存未知时不拦截，交由运行时兜底）；</li>
 *   <li>device=cpu/auto → 系统总内存 × {@link #USABLE_MEMORY_RATIO} ≥ minVramMb
 *       （GGUF 量化模型支持 mmap，CPU 内存是主要约束；探测失败时不拦截，避免误杀）。</li>
 * </ul>
 *
 * <p>探测结果进程内缓存；探测范式与 {@link DeviceSelector} 一致
 * （ProcessBuilder + 超时强杀 + AtomicReference 缓存）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public final class ModelHardwareFilter {

    /**
     * 硬件探测超时（秒）
     */
    private static final int DETECT_TIMEOUT_SECONDS = 3;

    /**
     * CPU / auto 设备：可用内存按系统总内存的比例估算（预留操作系统与 JVM 开销）
     */
    private static final double USABLE_MEMORY_RATIO = 0.8;

    /**
     * 系统总内存缓存（MB）；空=未探测
     */
    private static final AtomicReference<Long> TOTAL_RAM_MB = new AtomicReference<>();

    /**
     * modelhardwarefilter。
     */
    private ModelHardwareFilter() {
    }

    /**
     * 判断指定模型在当前硬件上是否可运行。
     *
     * @param modelId 模型标识
     * @return true 表示可运行；模型未注册返回 false
     */
    public static boolean isSupported(String modelId) {
        ModelRegistry.Entry entry = ModelRegistry.get(modelId);
        return entry != null && isSupported(entry);
    }

    /**
     * 判断注册条目在当前硬件上是否可运行。
     *
     * @param entry 注册条目，可为 空
     * @return true 表示可运行（无硬件配置或无法判定时放行）
     */
    public static boolean isSupported(ModelRegistry.Entry entry) {
        if (entry == null) {
            return false;
        }
        HardwareConfig hardwareConfig = entry.hardwareConfig();
        if (hardwareConfig == null) {
            return true;
        }
        long requiredMb = hardwareConfig.minVramMb();
        if (requiredMb <= 0) {
            return true;
        }
        if (hardwareConfig.isGpu()) {
            if (!DeviceSelector.isGpuUsable()) {
                return false;
            }
            long vramMb = DeviceSelector.gpuTotalVramMb();
            // 显存未知（-1）时不拦截，交由运行时兜底
            return vramMb < 0 || vramMb >= requiredMb;
        }
        // cpu / auto：按系统可用内存估算
        long totalMb = totalSystemMemoryMb();
        if (totalMb <= 0) {
            return true;
        }
        return requiredMb <= (long) (totalMb * USABLE_MEMORY_RATIO);
    }

    /**
     * 过滤出当前硬件可运行的模型条目。
     *
     * @param entries 候选条目，可为 空
     * @return 可运行条目列表（保持入参顺序）
     */
    public static List<ModelRegistry.Entry> filterSupported(Collection<ModelRegistry.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream().filter(ModelHardwareFilter::isSupported).collect(Collectors.toList());
    }

    /**
     * 探测系统总物理内存（MB），结果进程内缓存。
     *
     * @return 总内存（MB）；探测失败返回 -1
     */
    public static long totalSystemMemoryMb() {
        Long cached = TOTAL_RAM_MB.get();
        if (cached != null) {
            return cached;
        }
        synchronized (ModelHardwareFilter.class) {
            cached = TOTAL_RAM_MB.get();
            if (cached != null) {
                return cached;
            }
            long detected = probeTotalSystemMemoryMb();
            TOTAL_RAM_MB.set(detected);
            if (detected > 0) {
                log.info("[deeplearning-engine] 系统总内存探测: {} MB", detected);
            } else {
                log.debug("[deeplearning-engine] 系统总内存探测失败，硬件过滤对 cpu/auto 不拦截");
            }
            return detected;
        }
    }

    /**
     * 按操作系统探测系统总物理内存。
     *
     * @return 总内存（MB）；探测失败返回 -1
     */
    private static long probeTotalSystemMemoryMb() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            long mb = probeByCommand(new String[]{"wmic", "ComputerSystem", "get", "TotalPhysicalMemory"});
            if (mb > 0) {
                return mb;
            }
            // wmic 在新版 Windows 可能被移除，回退 PowerShell CIM
            return probeByCommand(new String[]{"powershell", "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory"});
        }
        return probeProcMeminfo();
    }

    /**
     * 执行输出单个字节数的命令并换算为 MB。
     *
     * <p>兼容 wmic（表头 + 数值行）与 PowerShell（单数值行）两种输出形态：
     * 取第一个纯数字行按字节换算。</p>
     *
     * @param cmd 命令及参数
     * @return 总内存（MB）；失败返回 -1
     */
    private static long probeByCommand(String[] cmd) {
        try {
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            if (process.exitValue() != 0) {
                return -1;
            }
            String output = new String(process.getInputStream().readAllBytes());
            for (String line : output.split("\r?\n")) {
                String trimmed = line.trim();
                // 跳过表头/空行，取第一个纯数字行（字节数）
                if (!trimmed.isEmpty() && trimmed.matches("[0-9]+")) {
                    try {
                        return Long.parseLong(trimmed) / (1024L * 1024L);
                    } catch (NumberFormatException ignored) {
                        return -1;
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            log.debug("[deeplearning-engine] 内存探测命令失败: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Linux /proc/meminfo 的 MemTotal 解析（kB → MB）。
     *
     * @return 总内存（MB）；失败返回 -1
     */
    private static long probeProcMeminfo() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) / 1024L;
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            log.debug("[deeplearning-engine] /proc/meminfo 读取失败: {}", e.getMessage());
            return -1;
        }
    }
}
