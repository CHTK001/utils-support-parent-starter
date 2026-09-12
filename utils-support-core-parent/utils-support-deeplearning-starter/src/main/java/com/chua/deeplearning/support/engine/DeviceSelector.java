package com.chua.deeplearning.support.engine;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
* 推理设备选择器。
*
* <p>统一解析设备意图并自动探测本机 GPU 可用性，供 DJL 模型工厂与各引擎模块使用：</p>
* <ul>
*   <li>{@code auto}（默认）— 自动探测：存在 NVIDIA 驱动且 classpath 为
*       {@code onnxruntime_gpu} 构件时选 GPU，否则 CPU</li>
*   <li>{@code cpu} — 强制 CPU</li>
*   <li>{@code gpu} / {@code cuda} — 强制 GPU（运行失败由调用方降级）</li>
* </ul>
*
* <p>配置来源优先级：调用方显式传入 &gt; 系统属性 {@code deeplearning.device}
* （取值 auto/cpu/gpu/cuda）&gt; 默认 auto。</p>
*
* <p>探测结果进程内缓存；可用 {@link #refresh()} 强制重探。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public final class DeviceSelector {

    /**
    * 设备设置系统属性名
     */
    public static final String PROP = "deeplearning.device";

    /**
    * onnxruntime_gpu 独有原生库（CPU 版构件不含 CUDA/tensorrt 提供者）
     */
    private static final String[] ORT_GPU_MARKERS = {
            "ai/onnxruntime/native/win-x64/onnxruntime_providers_cuda.dll",
            "ai/onnxruntime/native/linux-x64/libonnxruntime_providers_cuda.so",
    };

    /**
    * nvidia-smi 探测超时（秒）
     */
    private static final int DETECT_TIMEOUT_SECONDS = 3;

    /**
    * 探测结果缓存：空=未探测，"gpu"/"cpu"=已探测
     */
    private static final AtomicReference<String> DETECTED = new AtomicReference<>();

    /**
    * deviceselector。
     */
    private DeviceSelector() {
    }

    /**
    * 解析设备意图为实际设备。
    *
    * @param setting 调用方显式设备设置，可为 空
    * @return "gpu" 或 "cpu"（auto 模式下保证返回本机可用的设备）
     */
    public static String resolve(String setting) {
        String normalized = normalize(setting);
        if ("cpu".equals(normalized)) {
            return "cpu";
        }
        if ("gpu".equals(normalized)) {
            return "gpu";
        }
        // auto
        return isGpuUsable() ? "gpu" : "cpu";
    }

    /**
    * 归一化设备设置。
    *
    * @param setting 显式设置，可为 空
    * @return cpu / gpu / auto（缺省）
     */
    private static String normalize(String setting) {
        String value = (setting == null || setting.isBlank())
                ? System.getProperty(PROP, "auto")
                : setting;
        String lower = value.trim().toLowerCase();
        return switch (lower) {
            case "cpu" -> "cpu";
            case "gpu", "cuda" -> "gpu";
            default -> "auto";
        };
    }

    /**
    * 判断 GPU 是否可用。
    *
    * <p>需同时满足：NVIDIA 驱动可探测（nvidia-smi）且 classpath 含
    * {@code onnxruntime_gpu} 构件（CPU 版构件无法启用 CUDA EP）。</p>
    *
    * @return true 表示 GPU 可用
     */
    public static boolean isGpuUsable() {
        String cached = DETECTED.get();
        if (cached != null) {
            return "gpu".equals(cached);
        }
        synchronized (DeviceSelector.class) {
            cached = DETECTED.get();
            if (cached != null) {
                return "gpu".equals(cached);
            }
            boolean driver = isNvidiaDriverPresent();
            boolean artifact = hasOrtGpuArtifact();
            String result = (driver && artifact) ? "gpu" : "cpu";
            DETECTED.set(result);
            log.info("[deeplearning-engine] 设备自动探测: driver(nvidia-smi)={}, ort_gpu 构件={} -> {}",
                    driver, artifact, result.toUpperCase());
            return "gpu".equals(result);
        }
    }

    /**
    * NVIDIA GPU 探测结果缓存：空=未探测
     */
    private static final AtomicReference<GpuInfo> GPU_INFO = new AtomicReference<>();

    /**
    * NVIDIA GPU 基本信息（型号与显存）。
    *
    * @param name          显卡型号，如 "NVIDIA geforce GTX 1650"
    * @param totalVramMb   总显存（MB），未知为 -1
    * @return gpu信息的结果
     */
    public record GpuInfo(String name, long totalVramMb) {
    }

    /**
    * 清除缓存，下次探测重新执行。
     */
    public static void refresh() {
        DETECTED.set(null);
        GPU_INFO.set(null);
    }

    /**
    * 探测本机 NVIDIA GPU 型号与总显存。
    *
    * @return GPU 信息；无 NVIDIA GPU 或探测失败返回 空
     */
    public static GpuInfo detectGpu() {
        if (!isGpuUsable()) {
            return null;
        }
        GpuInfo cached = GPU_INFO.get();
        if (cached != null) {
            return cached;
        }
        synchronized (DeviceSelector.class) {
            cached = GPU_INFO.get();
            if (cached != null) {
                return cached;
            }
            for (String[] cmd : new String[][]{
                    {"nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"},
                    {"nvidia-smi.exe", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"}}) {
                try {
                    Process process = new ProcessBuilder(cmd)
                            .redirectErrorStream(true)
                            .start();
                    if (!process.waitFor(DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        continue;
                    }
                    if (process.exitValue() == 0) {
                        String output = new String(process.getInputStream().readAllBytes()).trim();
                        if (!output.isEmpty()) {
                            String[] parts = output.split(",");
                            String name = parts.length > 0 ? parts[0].trim() : "Unknown GPU";
                            long vramMb = -1;
                            if (parts.length > 1) {
                                try {
                                    vramMb = Long.parseLong(parts[1].trim().replaceAll("[^0-9]", ""));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            GpuInfo info = new GpuInfo(name, vramMb);
                            GPU_INFO.set(info);
                            log.info("[deeplearning-engine] GPU 探测: {} (显存 {} MB)", name, vramMb);
                            return info;
                        }
                    }
                } catch (Exception ignored) {
                    // 命令不存在或执行失败，尝试下一种写法
                }
            }
            GPU_INFO.set(new GpuInfo("Unknown GPU", -1));
            return GPU_INFO.get();
        }
    }

    /**
    * 获取本机 GPU 总显存（MB）。
    *
    * @return 显存大小（MB）；无 GPU 或探测失败返回 -1
     */
    public static long gpuTotalVramMb() {
        GpuInfo info = detectGpu();
        return info == null ? -1 : info.totalVramMb();
    }

    /**
    * 获取本机 GPU 型号名称。
    *
    * @return 型号名；无 GPU 返回 空
     */
    public static String gpuName() {
        GpuInfo info = detectGpu();
        return info == null ? null : info.name();
    }

    /**
    * 通过 nvidia-smi 探测 NVIDIA 驱动。
    *
    * @return true 表示命令执行成功且有输出
     */
    private static boolean isNvidiaDriverPresent() {
        for (String[] cmd : new String[][]{{"nvidia-smi", "-L"}, {"nvidia-smi.exe", "-L"}}) {
            try {
                Process process = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    continue;
                }
                if (process.exitValue() == 0) {
                    String output = new String(process.getInputStream().readAllBytes()).trim();
                    if (!output.isEmpty()) {
                        log.debug("[deeplearning-engine] nvidia-smi: {}", output.split("\n")[0]);
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // 命令不存在或执行失败视为无驱动，继续尝试下一种写法
            }
        }
        return false;
    }

    /**
    * 检测 类路径 是否为 onnxruntime_gpu 构件。
    *
    * <p>判定依据：GPU 版构件独带的 CUDA/TensorRT provider 原生库
    * （CPU 版构件不含，API 类则两个构件都有、不可作标记）。</p>
    *
    * @return true 表示存在 GPU 版构件
     */
    private static boolean hasOrtGpuArtifact() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = DeviceSelector.class.getClassLoader();
        }
        for (String marker : ORT_GPU_MARKERS) {
            if (loader.getResource(marker) != null) {
                return true;
            }
        }
        return false;
    }
}
